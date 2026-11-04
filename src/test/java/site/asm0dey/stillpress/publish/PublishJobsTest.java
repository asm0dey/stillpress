package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class PublishJobsTest {
    private static final String POST = "2023/01/14/месяц-ноябрь-год-2022/index.html";
    @Inject
    Jobs jobs;
    @Inject
    PublishQueue queue;
    @Inject
    PublishStatus status;
    @Inject
    SitePublisher publisher;
    @Inject
    TestStore store;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    @Inject
    SettingRepository settings;
    @Inject
    CommandLine.IFactory factory;
    @Inject
    RedirectRepository redirects;

    @BeforeEach
    void sample() throws Exception {
        store.reset();
        store.importSample();
    }

    @AfterEach
    void settle() {
        // the next test's reset must not race a background publish
        TestStore.await(
                () -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0
                || "failed".equals(status.current().state()),
                30
        );
    }

    @Test
    void queuedItemIsPublishedByTheTask() {
        var id = itemId(5994);
        QuarkusTransaction.requiringNew().run(() -> {
            queue.item(id);
            jobs.requestPublish();
        });
        TestStore.await(() -> db.fetchCount(PUBLISH_QUEUE) == 0, 20);
        assertThat(db.fetchCount(PUBLISH_QUEUE)).isZero();
        assertThat(dataDir.current().resolve(POST)).exists();
    }

    @Test
    void sweepPublishesARowWhoseTriggerWasLost() {
        // no requestPublish(): the after-commit trigger never fired
        queue.item(itemId(5994));
        TestStore.await(() -> db.fetchCount(PUBLISH_QUEUE) == 0, 40);
        assertThat(db.fetchCount(PUBLISH_QUEUE)).isZero();
        assertThat(dataDir.current().resolve(POST)).exists();
    }

    @Test
    void scheduledItemGoesLiveAtItsTime() {
        var at = Instant.now().plusSeconds(2);
        var id = itemId(101);
        var local = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        QuarkusTransaction.requiringNew().run(() -> {
            db
                .update(ITEM)
                .set(ITEM.STATUS, "future")
                .set(ITEM.PUBLISHED_AT, local)
                .set(ITEM.PUBLISHED_LOCAL, local)
                .setNull(ITEM.PERMALINK)
                .where(ITEM.ID.eq(id))
                .execute();
            jobs.scheduleGoLive(id, at);
        });
        TestStore.await(() -> "publish".equals(statusOf(id)) && db.fetchCount(PUBLISH_QUEUE) == 0, 30);
        var permalink = db.select(ITEM.PERMALINK).from(ITEM).where(ITEM.ID.eq(id)).fetchOne(ITEM.PERMALINK);
        assertThat(statusOf(id)).isEqualTo("publish");
        assertThat(permalink)
            .isEqualTo("/%04d/%02d/%02d/draft-post/"
                .formatted(local.getYear(), local.getMonthValue(), local.getDayOfMonth())
            );
        TestStore.await(() -> Files.exists(dataDir.current().resolve(permalink.substring(1) + "index.html")), 20);
        assertThat(dataDir.current().resolve(permalink.substring(1) + "index.html")).exists();
    }

    @Test
    void goLiveOfReDatedPostMovesItsPermalink() {
        var id = itemId(5994);
        var old = db.select(ITEM.PERMALINK).from(ITEM).where(ITEM.ID.eq(id)).fetchOne(ITEM.PERMALINK);
        // a published post re-dated into the future, now due
        QuarkusTransaction
            .requiringNew()
            .run(() -> db
                .update(ITEM)
                .set(ITEM.STATUS, "future")
                .set(ITEM.PUBLISHED_AT, LocalDateTime.of(2025, 5, 6, 7, 0))
                .set(ITEM.PUBLISHED_LOCAL, LocalDateTime.of(2025, 5, 6, 10, 0))
                .where(ITEM.ID.eq(id))
                .execute());
        jobs.goLive(id + "@0");
        var moved = db.select(ITEM.PERMALINK).from(ITEM).where(ITEM.ID.eq(id)).fetchOne(ITEM.PERMALINK);
        assertThat(moved).isEqualTo("/2025/05/06/месяц-ноябрь-год-2022/");
        assertThat(redirects.find(old).orElseThrow().getToPath()).isEqualTo(moved);
    }

    @Test
    void staleGoLiveIsANoOp() {
        var id = itemId(101);
        var permalink = db.select(ITEM.PERMALINK).from(ITEM).where(ITEM.ID.eq(id)).fetchOne(ITEM.PERMALINK);
        QuarkusTransaction
            .requiringNew()
            .run(() -> jobs.scheduleGoLive(id, Instant.now()));
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.GO_LIVE)) == 0, 20);
        assertThat(db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.GO_LIVE))).isZero();
        assertThat(statusOf(id)).isEqualTo("draft");
        assertThat(db.fetchCount(PUBLISH_QUEUE)).isZero();
        assertThat(db.select(ITEM.PERMALINK).from(ITEM).where(ITEM.ID.eq(id)).fetchOne(ITEM.PERMALINK)).isEqualTo(
                permalink
        );
    }

    @Test
    void rolledBackSaveLeavesNoQueueRowOrTask() {
        var id = itemId(5994);
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            queue.item(id);
            jobs.scheduleGoLive(id, Instant.now().plusSeconds(3600));
            throw new IllegalStateException("roll back");
        })).hasMessage("roll back");
        assertThat(db.fetchCount(PUBLISH_QUEUE)).isZero();
        assertThat(db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.GO_LIVE))).isZero();
    }

    @Test
    void cliPublishAndTaskNeverOverlap() throws Exception {
        settings.put("posts_per_page", "1");
        var events = new CopyOnWriteArrayList<String>();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                var m = r.getMessage();
                if (m.startsWith("Publish started")) {
                    events.add("start " + Thread.currentThread().getName());
                } else if (m.startsWith("Published ")) {
                    events.add("end " + Thread.currentThread().getName());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        var logger = Logger.getLogger(SitePublisher.class.getName());
        logger.addHandler(handler);
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                queue.all();
                jobs.requestPublish();
            });
            TestStore.await(() -> !events.isEmpty(), 20);
            var out = new StringWriter();
            var cli = Thread.ofPlatform().name("cli").start(() -> {
                var cmd = new CommandLine(PublishCommand.class, factory);
                cmd.setOut(new PrintWriter(out, true));
                cmd.execute();
            });
            cli.join(30_000);
            TestStore.await(() -> events.size() >= 4, 20);
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(events).hasSize(4);
        assertThat(events.stream().map(e -> e.split(" ")[0]).toList()).containsExactly("start", "end", "start", "end");
        assertThat(events.subList(2, 4)).containsExactly("start cli", "end cli");
        assertThat(db.fetchCount(PUBLISH_QUEUE)).isZero();
        assertThat(dataDir.current().resolve(POST)).exists();
        assertThat(dataDir.current().resolve("page/3/index.html")).exists();
    }

    @Test
    void failedPublishKeepsRowsAndReportsFailed() throws Exception {
        var releases = dataDir.releases();
        var before = Files.getPosixFilePermissions(releases);
        Files.setPosixFilePermissions(releases, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                queue.item(itemId(5994));
                jobs.requestPublish();
            });
            TestStore.await(() -> "failed".equals(status.current().state()), 20);
        } finally {
            Files.setPosixFilePermissions(releases, before);
        }
        assertThat(status.current().state()).isEqualTo("failed");
        assertThat(status.current().error()).isNotBlank();
        assertThat(db.fetchCount(PUBLISH_QUEUE)).isEqualTo(1);
    }

    private String statusOf(long id) {
        return db.select(ITEM.STATUS).from(ITEM).where(ITEM.ID.eq(id)).fetchOne(ITEM.STATUS);
    }

    private long itemId(long sourceId) {
        return db.select(ITEM.ID).from(ITEM).where(ITEM.SOURCE_ID.eq(sourceId)).fetchOne(ITEM.ID);
    }
}
