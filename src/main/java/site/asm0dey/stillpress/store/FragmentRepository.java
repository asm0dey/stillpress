package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.ITEM_FRAGMENT;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;

/**
 * An item's rendered archive entry and feed item, kept between publishes; whoever changes what either shows deletes
 * the item's fragments.
 */
@ApplicationScoped
public class FragmentRepository {
    public static final String ENTRY = "entry";
    public static final String FEED = "feed";
    private final DSLContext db;

    FragmentRepository(DSLContext db) {
        this.db = db;
    }

    public Map<Long, String> find(String kind, Collection<Long> ids) {
        return db
            .select(ITEM_FRAGMENT.ITEM_ID, ITEM_FRAGMENT.HTML)
            .from(ITEM_FRAGMENT)
            .where(ITEM_FRAGMENT.KIND.eq(kind).and(ITEM_FRAGMENT.ITEM_ID.in(ids)))
            .fetchMap(ITEM_FRAGMENT.ITEM_ID, ITEM_FRAGMENT.HTML);
    }

    /**
     * Inserts fragments of one kind for items that have none; one statement.
     */
    public void put(String kind, Map<Long, String> htmlById) {
        if (htmlById.isEmpty()) {
            return;
        }
        var insert = db.insertInto(ITEM_FRAGMENT, ITEM_FRAGMENT.ITEM_ID, ITEM_FRAGMENT.KIND, ITEM_FRAGMENT.HTML);
        htmlById.forEach((id, html) -> insert.values(id, kind, html));
        insert.execute();
    }

    /**
     * Every kind of the items' fragments.
     */
    public void delete(Collection<Long> ids) {
        db.deleteFrom(ITEM_FRAGMENT).where(ITEM_FRAGMENT.ITEM_ID.in(ids)).execute();
    }

    public void clear() {
        db.deleteFrom(ITEM_FRAGMENT).execute();
    }
}
