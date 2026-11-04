package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;

@QuarkusTest
class PublishQueueTest {
    @Inject
    PublishQueue queue;
    @Inject
    DSLContext db;
    @Inject
    TestStore store;

    @BeforeEach
    void clean() {
        store.reset();
    }

    @Test
    void snapshotFoldsRowsByKind() {
        queue.item(5);
        queue.item(5);
        queue.base("/category/travel/");
        queue.media();
        var b = queue.snapshot().orElseThrow();
        assertThat(b.items()).containsExactly(5L);
        assertThat(b.bases()).containsExactly("/category/travel/");
        assertThat(b.media()).isEqualTo(true);
        assertThat(b.all()).isEqualTo(false);
        queue.done(b);
        assertThat(queue.snapshot()).isEmpty();
    }

    @Test
    void doneKeepsALowerIdRowThatCommittedAfterTheSnapshot() {
        queue.item(1);
        var b = queue.snapshot().orElseThrow();
        var late = Collections.max(b.ids()) - 1;
        db
            .insertInto(PUBLISH_QUEUE, PUBLISH_QUEUE.ID, PUBLISH_QUEUE.KIND, PUBLISH_QUEUE.REF, PUBLISH_QUEUE.QUEUED_AT)
            .values(late, "item", "2", LocalDateTime.now(ZoneOffset.UTC))
            .execute();
        queue.done(b);
        assertThat(db.select(PUBLISH_QUEUE.ID).from(PUBLISH_QUEUE).fetch(PUBLISH_QUEUE.ID)).containsExactly(late);
    }
}
