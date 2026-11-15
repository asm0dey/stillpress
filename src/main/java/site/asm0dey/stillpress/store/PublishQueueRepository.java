package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.pojos.PublishQueue;

/**
 * Rows of the {@code publish_queue} table; never starts a transaction.
 */
@ApplicationScoped
public class PublishQueueRepository {
    private final DSLContext db;

    PublishQueueRepository(DSLContext db) {
        this.db = db;
    }

    public void add(String kind, String ref) {
        db
            .insertInto(PUBLISH_QUEUE, PUBLISH_QUEUE.KIND, PUBLISH_QUEUE.REF, PUBLISH_QUEUE.QUEUED_AT)
            .values(kind, ref, LocalDateTime.now(ZoneOffset.UTC))
            .execute();
    }

    /**
     * {@link #add} for many, in one statement.
     */
    public void addAll(String kind, Collection<String> refs) {
        if (refs.isEmpty()) {
            return;
        }
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var insert = db.insertInto(PUBLISH_QUEUE, PUBLISH_QUEUE.KIND, PUBLISH_QUEUE.REF, PUBLISH_QUEUE.QUEUED_AT);
        refs.forEach(ref -> insert.values(kind, ref, now));
        insert.execute();
    }

    public List<PublishQueue> all() {
        return db.selectFrom(PUBLISH_QUEUE).fetchInto(PublishQueue.class);
    }

    public boolean any() {
        return db.fetchExists(PUBLISH_QUEUE);
    }

    public boolean anyQueuedBefore(LocalDateTime t) {
        return db.fetchExists(PUBLISH_QUEUE, PUBLISH_QUEUE.QUEUED_AT.lt(t));
    }

    public void delete(Collection<Long> ids) {
        db.deleteFrom(PUBLISH_QUEUE).where(PUBLISH_QUEUE.ID.in(ids)).execute();
    }
}
