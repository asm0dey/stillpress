package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.TermRecord;

@ApplicationScoped
public class TermRepository {
    @Inject
    DSLContext db;

    public List<TermRecord> forItem(long itemId) {
        return db
            .select(TERM.fields())
            .from(TERM)
            .join(ITEM_TERM)
            .on(ITEM_TERM.TERM_ID.eq(TERM.ID))
            .where(ITEM_TERM.ITEM_ID.eq(itemId))
            .orderBy(TERM.TAXONOMY, TERM.NAME)
            .fetchInto(TERM);
    }

    public Optional<TermRecord> findById(long id) {
        return db.selectFrom(TERM).where(TERM.ID.eq(id)).fetchOptional();
    }

    public List<TermRecord> all() {
        return db.selectFrom(TERM).fetch();
    }

    /**
     * Term ids of every item, keyed by item id.
     */
    public Map<Long, List<Long>> termIdsByItem() {
        return db
            .select(ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID)
            .from(ITEM_TERM)
            .fetchGroups(ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID);
    }
}
