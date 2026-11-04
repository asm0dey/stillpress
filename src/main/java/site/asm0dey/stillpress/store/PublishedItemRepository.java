package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.PUBLISHED_ITEM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.Records;

/**
 * Where each item went in the live release: its permalink and the listing bases it appears in. Call inside a
 * transaction when the writes must land together.
 */
@ApplicationScoped
public class PublishedItemRepository {
    @Inject
    DSLContext db;

    /**
     * @param bases decoded listing base paths such as {@code /category/travel/}
     */
    public record Row(long itemId, String permalink, List<String> bases) {}

    public Map<Long, Row> find(Collection<Long> ids) {
        var out = new HashMap<Long, Row>();
        db
            .select(PUBLISHED_ITEM.ITEM_ID, PUBLISHED_ITEM.PERMALINK, PUBLISHED_ITEM.BASES)
            .from(PUBLISHED_ITEM)
            .where(PUBLISHED_ITEM.ITEM_ID.in(ids))
            .fetch(Records.mapping((Long id, String permalink, String bases) -> new Row(
                    id,
                    permalink,
                    bases.isEmpty() ? List.of() : List.of(bases.split("\n"))
            )))
            .forEach(r -> out.put(r.itemId(), r));
        return out;
    }

    public boolean any() {
        return db.fetchExists(PUBLISHED_ITEM);
    }

    public void replaceAll(List<Row> rows) {
        db.deleteFrom(PUBLISHED_ITEM).execute();
        insert(rows);
    }

    public void upsert(List<Row> rows) {
        delete(rows.stream().map(Row::itemId).toList());
        insert(rows);
    }

    public void delete(Collection<Long> ids) {
        db.deleteFrom(PUBLISHED_ITEM).where(PUBLISHED_ITEM.ITEM_ID.in(ids)).execute();
    }

    private void insert(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var insert =
                db.insertInto(PUBLISHED_ITEM, PUBLISHED_ITEM.ITEM_ID, PUBLISHED_ITEM.PERMALINK, PUBLISHED_ITEM.BASES);
        rows.forEach(r -> insert.values(r.itemId(), r.permalink(), String.join("\n", r.bases())));
        insert.execute();
    }
}
