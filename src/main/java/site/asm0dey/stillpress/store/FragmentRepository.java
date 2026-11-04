package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.ITEM_FRAGMENT;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;

/**
 * An item's rendered archive entry, kept between publishes; whoever changes what an entry shows deletes it.
 */
@ApplicationScoped
public class FragmentRepository {
    @Inject
    DSLContext db;

    public Map<Long, String> find(Collection<Long> ids) {
        return db
            .select(ITEM_FRAGMENT.ITEM_ID, ITEM_FRAGMENT.HTML)
            .from(ITEM_FRAGMENT)
            .where(ITEM_FRAGMENT.ITEM_ID.in(ids))
            .fetchMap(ITEM_FRAGMENT.ITEM_ID, ITEM_FRAGMENT.HTML);
    }

    /**
     * Inserts fragments of items that have none; one statement.
     */
    public void put(Map<Long, String> htmlById) {
        if (htmlById.isEmpty()) {
            return;
        }
        var insert = db.insertInto(ITEM_FRAGMENT, ITEM_FRAGMENT.ITEM_ID, ITEM_FRAGMENT.HTML);
        htmlById.forEach(insert::values);
        insert.execute();
    }

    public void delete(Collection<Long> ids) {
        db.deleteFrom(ITEM_FRAGMENT).where(ITEM_FRAGMENT.ITEM_ID.in(ids)).execute();
    }

    public void clear() {
        db.deleteFrom(ITEM_FRAGMENT).execute();
    }
}
