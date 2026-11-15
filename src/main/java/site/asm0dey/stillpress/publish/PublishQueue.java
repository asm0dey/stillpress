package site.asm0dey.stillpress.publish;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.store.PublishQueueRepository;

/**
 * What changed since the last publish. Callers enqueue inside their own transaction, so a save that rolls back leaves
 * no row; this class never starts one.
 */
@ApplicationScoped
public class PublishQueue {
    private final PublishQueueRepository rows;

    PublishQueue(PublishQueueRepository rows) {
        this.rows = rows;
    }

    /**
     * Everything read by one snapshot, folded by kind; {@code ids} are the queue rows it covers.
     */
    public record Batch(Set<Long> ids, Set<Long> items, Set<String> bases, boolean media, boolean all) {}

    public void item(long id) {
        add("item", String.valueOf(id));
    }

    /**
     * {@link #item} for many, in one statement.
     */
    public void items(Collection<Long> ids) {
        rows.addAll("item", ids.stream().map(String::valueOf).toList());
    }

    /**
     * {@link #base} for many, in one statement.
     */
    public void bases(Collection<String> bases) {
        rows.addAll("base", bases);
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
        rows.add(kind, ref);
    }

    /**
     * Empty when nothing is queued.
     */
    public Optional<Batch> snapshot() {
        var queued = rows.all();
        if (queued.isEmpty()) {
            return Optional.empty();
        }
        var items = new LinkedHashSet<Long>();
        var bases = new LinkedHashSet<String>();
        boolean media = false, all = false;
        var ids = new HashSet<Long>();
        for (var r : queued) {
            ids.add(r.id());
            switch (r.kind()) {
                case "item" -> items.add(Long.parseLong(r.ref()));
                case "base" -> bases.add(r.ref());
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
        rows.delete(b.ids());
    }
}
