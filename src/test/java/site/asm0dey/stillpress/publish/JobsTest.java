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
    void requestPublishTwiceLeavesOneExecution() {
        // counted before commit: once committed, the scheduler picks the row up and removes it
        var inside = new AtomicInteger();
        rolledBack(() -> {
            jobs.requestPublish();
            jobs.requestPublish();
            inside.set(rows("publish-site"));
        });
        assertThat(inside).hasValue(1);
    }

    @Test
    void rolledBackRequestsLeaveNoTask() {
        rolledBack(() -> {
            jobs.requestPublish();
            jobs.scheduleGoLive(7, Instant.now().plusSeconds(3600));
        });
        assertThat(rows("publish-site")).isZero();
        assertThat(rows("go-live")).isZero();
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
