package site.asm0dey.stillpress.publish;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.content.Permalinker;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.PublishLock;
import site.asm0dey.stillpress.store.PublishQueueRepository;
import site.asm0dey.stillpress.store.TaskStore;

/**
 * Background work backed by the {@code scheduled_tasks} table: publishing and go-live of scheduled items. The
 * scheduler runs only when the HTTP server is up; every process (the CLI included) can still enqueue.
 */
@ApplicationScoped
public class Jobs {
    public static final String PUBLISH_SITE = "publish-site";
    public static final String GO_LIVE = "go-live";
    public static final String PUBLISH_SWEEP = "publish-sweep";
    private static final Logger LOG = Logger.getLogger(Jobs.class);
    private final TaskStore tasks;
    private final TransactionSynchronizationRegistry registry;
    // as SetupToken: Main turns this off for CLI commands
    private final boolean httpEnabled;
    private final PublishLock lock;
    private final PublishQueue queue;
    private final SitePublisher publisher;
    private final Permalinker permalinks;
    private final ItemRepository items;
    private final PublishQueueRepository queued;
    private final Duration sweepInterval;
    private final Duration pollingInterval;
    // a failing run retries a minute later instead of spinning
    private final Task<Void> publishSite = Tasks
        .oneTime(PUBLISH_SITE)
        .onFailure(new FailureHandler.OnFailureRetryLater<>(Duration.ofMinutes(1)))
        .execute((instance, ctx) -> drain());
    private final Task<Void> goLive = Tasks
        .oneTime(GO_LIVE)
        .onFailure(new FailureHandler.OnFailureRetryLater<>(Duration.ofMinutes(1)))
        .execute((instance, ctx) -> goLive(instance.getId()));
    private RecurringTask<Void> publishSweep;
    private SchedulerClient client;
    // db-scheduler's Scheduler is thread-safe
    @SuppressWarnings("java:S3077")
    private volatile Scheduler scheduler;

    Jobs(
            TaskStore tasks,
            TransactionSynchronizationRegistry registry,
            @ConfigProperty(name = "quarkus.http.host-enabled", defaultValue = "true") boolean httpEnabled,
            PublishLock lock,
            PublishQueue queue,
            SitePublisher publisher,
            Permalinker permalinks,
            ItemRepository items,
            PublishQueueRepository queued,
            @ConfigProperty(name = "stillpress.publish.sweep-interval", defaultValue = "1m") Duration sweepInterval,
            @ConfigProperty(name = "stillpress.publish.polling-interval", defaultValue = "10s") Duration pollingInterval
    ) {
        this.tasks = tasks;
        this.registry = registry;
        this.httpEnabled = httpEnabled;
        this.lock = lock;
        this.queue = queue;
        this.publisher = publisher;
        this.permalinks = permalinks;
        this.items = items;
        this.queued = queued;
        this.sweepInterval = sweepInterval;
        this.pollingInterval = pollingInterval;
    }

    @PostConstruct
    void init() {
        publishSweep = Tasks
            .recurring(PUBLISH_SWEEP, Schedules.fixedDelay(sweepInterval))
            .execute((i, ctx) -> sweep());
        client = tasks.client(publishSite, goLive, publishSweep).serializer(new JacksonSerializer()).build();
    }

    void onStart(@Observes StartupEvent e) {
        if (!httpEnabled) {
            return;
        }
        scheduler = tasks
            .scheduler(publishSite, goLive)
            .startTasks(publishSweep)
            .threads(2)
            .pollingInterval(pollingInterval)
            .serializer(new JacksonSerializer())
            .alwaysPersistTimestampInUTC()
            .build();
        scheduler.start();
    }

    void onStop(@Observes ShutdownEvent e) {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    public SchedulerClient client() {
        return scheduler != null ? scheduler : client;
    }

    /**
     * Asks for a publish; one pending request covers every change made before it runs. Inside a transaction the
     * request is scheduled after it commits (a rollback asks for nothing): scheduling inside would make concurrent
     * saves collide on the one {@code site} row, which PostgreSQL turns into an aborted transaction. Without a
     * transaction it is scheduled at once.
     */
    public void requestPublish() {
        if (registry.getTransactionStatus() == Status.STATUS_ACTIVE) {
            registry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {}

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        QuarkusTransaction.suspendingExisting().run(Jobs.this::scheduleNow);
                    }
                }
            });
        } else {
            scheduleNow();
        }
    }

    private void scheduleNow() {
        client().scheduleIfNotExists(publishSite.instance("site"), Instant.now());
        var s = scheduler;
        if (s != null) {
            s.triggerCheckForDueExecutions();
        }
    }

    /**
     * Publishes batch after batch under the lock until nothing is queued. A failure leaves the rows queued.
     */
    void drain() {
        try {
            lock.holding(() -> {
                for (var b = queue.snapshot(); b.isPresent(); b = queue.snapshot()) {
                    publisher.publish(b.get());
                    queue.done(b.get());
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Publishing failed; the changes stay queued", e);
            throw e;
        }
    }

    /**
     * Catches rows whose after-commit request was lost (a crash between commit and scheduling, or a row queued while
     * the last run was finishing): a row older than one sweep period with no publish pending gets one.
     */
    void sweep() {
        var stale = queued.anyQueuedBefore(LocalDateTime.now(ZoneOffset.UTC).minus(sweepInterval));
        if (stale && client().getScheduledExecution(TaskInstanceId.of(PUBLISH_SITE, "site")).isEmpty()) {
            requestPublish();
        }
    }

    /**
     * A task left over from a rescheduled or unscheduled item changes nothing: the item is no longer due.
     */
    void goLive(String instance) {
        var id = Long.parseLong(instance.substring(0, instance.indexOf('@')));
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                if (items.publishIfDue(id, LocalDateTime.now(ZoneOffset.UTC))) {
                    // first permalink, or a re-dated post's new date-derived one (with a redirect from the old)
                    permalinks.assignPermalink(items.find(id).orElseThrow());
                    queue.item(id);
                    requestPublish();
                }
            });
    }

    /**
     * Makes a scheduled item public at {@code at}.
     */
    public void scheduleGoLive(long itemId, Instant at) {
        client().scheduleIfNotExists(goLive.instance(itemId + "@" + at.getEpochSecond()), at);
    }
}
