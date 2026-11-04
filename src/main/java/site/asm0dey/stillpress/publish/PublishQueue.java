package site.asm0dey.stillpress.publish;

import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;

/**
 * What changed since the last publish. Callers enqueue inside their own transaction, so a save that rolls back leaves
 * no row; this class never starts one.
 */
@ApplicationScoped
public class PublishQueue {
    @Inject
    DSLContext db;

    /**
     * Everything read by one snapshot, folded by kind; {@code ids} are the queue rows it covers.
     */
    public record Batch(Set<Long> ids, Set<Long> items, Set<String> bases, boolean media, boolean all) {}

    public void item(long id) {
        add("item", String.valueOf(id));
    }

    /**
     * @param base a listing base path such as {@code /category/travel/}
     */
    public void base(String base) {
        add("base", base);
    }

    public void media() {
        add("media", null);
    }

    public void all() {
        add("all", null);
    }

    private void add(String kind, String ref) {
        db
            .insertInto(PUBLISH_QUEUE, PUBLISH_QUEUE.KIND, PUBLISH_QUEUE.REF, PUBLISH_QUEUE.QUEUED_AT)
            .values(kind, ref, LocalDateTime.now(ZoneOffset.UTC))
            .execute();
    }

    /**
     * Empty when nothing is queued.
     */
    public Optional<Batch> snapshot() {
        var rows = db.select(PUBLISH_QUEUE.ID, PUBLISH_QUEUE.KIND, PUBLISH_QUEUE.REF).from(PUBLISH_QUEUE).fetch();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var items = new LinkedHashSet<Long>();
        var bases = new LinkedHashSet<String>();
        boolean media = false, all = false;
        var ids = new HashSet<Long>();
        for (var r : rows) {
            ids.add(r.get(PUBLISH_QUEUE.ID));
            switch (r.get(PUBLISH_QUEUE.KIND)) {
                case "item" -> items.add(Long.parseLong(r.get(PUBLISH_QUEUE.REF)));
                case "base" -> bases.add(r.get(PUBLISH_QUEUE.REF));
                case "media" -> media = true;
                default -> all = true;
            }
        }
        return Optional.of(new Batch(ids, items, bases, media, all));
    }

    /**
     * Removes exactly the rows {@code b} read; rows queued since stay, even those with a lower id that committed late.
     */
    public void done(Batch b) {
        db.deleteFrom(PUBLISH_QUEUE).where(PUBLISH_QUEUE.ID.in(b.ids())).execute();
    }
}
