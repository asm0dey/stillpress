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
     * Everything queued up to {@code upTo}, folded by kind.
     */
    public record Batch(long upTo, Set<Long> items, Set<String> bases, boolean media, boolean all) {}

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
        long upTo = 0;
        for (var r : rows) {
            upTo = Math.max(upTo, r.value1());
            switch (r.value2()) {
                case "item" -> items.add(Long.parseLong(r.value3()));
                case "base" -> bases.add(r.value3());
                case "media" -> media = true;
                default -> all = true;
            }
        }
        return Optional.of(new Batch(upTo, items, bases, media, all));
    }

    /**
     * Removes the rows {@code b} covers; rows queued since stay.
     */
    public void done(Batch b) {
        db.deleteFrom(PUBLISH_QUEUE).where(PUBLISH_QUEUE.ID.le(b.upTo())).execute();
    }
}
