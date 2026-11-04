package site.asm0dey.stillpress.publish;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import module java.base;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Background work backed by the {@code scheduled_tasks} table: publishing and go-live of scheduled items. The
 * scheduler runs only when the HTTP server is up; every process (the CLI included) can still enqueue.
 */
@ApplicationScoped
public class Jobs {
    public static final String PUBLISH_SITE = "publish-site";
    public static final String GO_LIVE = "go-live";
    public static final String PUBLISH_SWEEP = "publish-sweep";
    @Inject
    DataSource dataSource;
    // as SetupToken: Main turns this off for CLI commands
    @ConfigProperty(name = "quarkus.http.host-enabled", defaultValue = "true")
    boolean httpEnabled;
    // bodies arrive with the publish pipeline
    private final Task<Void> publishSite = Tasks
        .oneTime(PUBLISH_SITE)
        .execute((instance, ctx) -> {});
    private final Task<Void> goLive = Tasks.oneTime(GO_LIVE).execute((instance, ctx) -> {});
    private final RecurringTask<Void> publishSweep =
            Tasks
        .recurring(PUBLISH_SWEEP, Schedules.fixedDelay(Duration.ofMinutes(1)))
        .execute((instance, ctx) -> {});
    private SchedulerClient client;
    private volatile Scheduler scheduler;

    @PostConstruct
    void init() {
        client = SchedulerClient.Builder
            .create(dataSource, publishSite, goLive, publishSweep)
            .serializer(new JacksonSerializer())
            .build();
    }

    void onStart(@Observes StartupEvent e) {
        if (!httpEnabled) {
            return;
        }
        scheduler = Scheduler
            .create(dataSource, publishSite, goLive)
            .startTasks(publishSweep)
            .threads(2)
            .pollingInterval(Duration.ofSeconds(10))
            .serializer(new JacksonSerializer())
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
     * Asks for a publish; one pending request covers every change made before it runs. Call inside the transaction
     * that made the change: the row commits or rolls back with it.
     */
    public void requestPublish() {
        client().scheduleIfNotExists(publishSite.instance("site"), Instant.now());
        var s = scheduler;
        if (s != null) {
            s.triggerCheckForDueExecutions();
        }
    }

    /**
     * Makes a scheduled item public at {@code at}.
     */
    public void scheduleGoLive(long itemId, Instant at) {
        client().scheduleIfNotExists(goLive.instance(itemId + "@" + at.getEpochSecond()), at);
    }
}
