package site.asm0dey.stillpress.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;

@QuarkusTest
@TestProfile(PostgresProfile.class)
class PostgresJobsTest {
    @Inject
    Jobs jobs;
    @Inject
    PublishQueue queue;
    @Inject
    DSLContext db;
    @Inject
    TestStore store;
    @Inject
    ItemService items;
    @Inject
    AuthorRepository users;

    @Test
    void concurrentCreatesGetDistinctPublicIds() throws Exception {
        store.reset();
        var author = users.create("writer", "writer@example.org", "Writer", Role.AUTHOR).getId();
        var start = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var creates = IntStream
                .range(0, 8)
                .mapToObj(i -> pool.submit(() -> QuarkusTransaction
                    .requiringNew()
                    .call(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        var id = items.create("post", author).getSourceId();
                        // keep the transaction open so the other insert meets this uncommitted id
                        Thread.sleep(300);
                        return id;
                    })))
                .toList();
            var ids = new ArrayList<Long>();
            for (var c : creates) {
                ids.add(c.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).doesNotHaveDuplicates().hasSize(8);
        }
    }

    @Test
    void anUnknownAuthorFailsWithTheForeignKeyErrorNotAPublicIdError() {
        store.reset();
        assertThatThrownBy(() -> QuarkusTransaction
            .requiringNew()
            .call(() -> items.create("post", 987654L)))
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .hasStackTraceContaining("foreign key")
            .hasMessageNotContaining("No free public id");
    }

    @Test
    void concurrentSavesBothCommitAndLeaveOnePublishRequest() throws Exception {
        store.reset();
        var bothEnqueued = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var saves = List
                .of(1L, 2L)
                .stream()
                .map(id -> pool.submit(() -> QuarkusTransaction
                    .requiringNew()
                    .run(() -> {
                        queue.item(id);
                        jobs.requestPublish();
                        // both transactions are open with their request registered before either commits
                        try {
                            bothEnqueued.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })))
                .toList();
            for (var s : saves) {
                s.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq("publish-site"))).isLessThanOrEqualTo(1);
    }
}
