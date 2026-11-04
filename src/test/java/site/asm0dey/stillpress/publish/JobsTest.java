package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;

@QuarkusTest
class JobsTest {
    @Inject
    Jobs jobs;
    @Inject
    DSLContext db;
    @Inject
    TestStore store;

    @BeforeEach
    void clean() {
        store.reset();
    }

    private int rows(String task) {
        return db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(task));
    }

    private static void rolledBack(Runnable work) {
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            work.run();
            throw new IllegalStateException("roll back");
        })).hasMessage("roll back");
    }

    @Test
    void requestPublishWaitsForTheCommit() throws Exception {
        var inside = new AtomicInteger(-1);
        QuarkusTransaction.requiringNew().run(() -> {
            jobs.requestPublish();
            jobs.requestPublish();
            inside.set(rows("publish-site"));
        });
        assertThat(inside).hasValue(0);
    }

    @Test
    void rolledBackRequestsLeaveNoTask() throws Exception {
        var peak = peakPublishSiteRows(() -> rolledBack(() -> {
            jobs.requestPublish();
            jobs.scheduleGoLive(7, Instant.now().plusSeconds(3600));
        }));
        assertThat(peak).isZero();
        assertThat(rows("go-live")).isZero();
    }

    @Test
    void controlCommittedRequestIsSeen() throws Exception {
        // shows the watcher can see a committed request, so the zero above is not vacuous; the scheduler may
        // consume the row between two reads, so it gets a few tries
        var peak = 0;
        for (var attempt = 0; attempt < 5 && peak == 0; attempt++) {
            peak = peakPublishSiteRows(() -> QuarkusTransaction.requiringNew().run(jobs::requestPublish));
        }
        assertThat(peak).isEqualTo(1);
    }

    /**
     * The most publish-site rows seen while {@code action} ran and the scheduler had a second to react. The scheduler
     * deletes the row once the task ran, so a spinning reader watches the whole time.
     */
    private int peakPublishSiteRows(Runnable action) throws InterruptedException {
        var peak = new AtomicInteger();
        var stop = new AtomicBoolean();
        var watcher = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                peak.accumulateAndGet(rows("publish-site"), Math::max);
            }
        });
        action.run();
        Thread.sleep(1000);
        stop.set(true);
        watcher.join();
        return peak.get();
    }

    @Test
    void committedGoLiveStaysUntilDue() {
        var at = Instant.now().plusSeconds(3600);
        QuarkusTransaction
            .requiringNew()
            .run(() -> jobs.scheduleGoLive(7, at));
        assertThat(db
            .select(SCHEDULED_TASKS.TASK_INSTANCE)
            .from(SCHEDULED_TASKS)
            .where(SCHEDULED_TASKS.TASK_NAME.eq("go-live"))
            .fetch(SCHEDULED_TASKS.TASK_INSTANCE)
        )
            .containsExactly("7@" + at.getEpochSecond());
    }
}
