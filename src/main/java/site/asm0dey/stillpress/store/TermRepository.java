package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.content.Slugs;
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

    public Optional<TermRecord> findBySlug(String taxonomy, String slug) {
        return db.selectFrom(TERM).where(TERM.TAXONOMY.eq(taxonomy), TERM.SLUG.eq(slug)).fetchOptional();
    }

    /**
     * A new term; its slug is {@code Slugs.title(name)}, moved on to {@code -2}, {@code -3}... when the taxonomy
     * already has it.
     */
    public TermRecord create(String taxonomy, String name, Long parentId) {
        var base = Slugs.title(name);
        var slug = base;
        for (int n = 2; findBySlug(taxonomy, slug).isPresent(); n++) {
            slug = base + "-" + n;
        }
        var r = db.newRecord(TERM);
        r.setTaxonomy(taxonomy);
        r.setName(name);
        r.setSlug(slug);
        r.setParentId(parentId);
        r.store();
        return r;
    }

    public List<TermRecord> byTaxonomy(String taxonomy) {
        return db.selectFrom(TERM).where(TERM.TAXONOMY.eq(taxonomy)).orderBy(TERM.NAME).fetch();
    }

    public Set<Long> idsForItem(long itemId, String taxonomy) {
        return Set.copyOf(db
            .select(TERM.ID)
            .from(TERM)
            .join(ITEM_TERM)
            .on(ITEM_TERM.TERM_ID.eq(TERM.ID))
            .where(ITEM_TERM.ITEM_ID.eq(itemId), TERM.TAXONOMY.eq(taxonomy))
            .fetch(TERM.ID)
        );
    }

    /**
     * Replaces the item's terms of {@code taxonomy} with those of {@code ids} that exist in it.
     */
    public void setForItem(long itemId, String taxonomy, Collection<Long> ids) {
        db
            .deleteFrom(ITEM_TERM)
            .where(
                    ITEM_TERM.ITEM_ID.eq(itemId),
                    ITEM_TERM.TERM_ID.in(DSL.select(TERM.ID).from(TERM).where(TERM.TAXONOMY.eq(taxonomy)))
            )
            .execute();
        if (!ids.isEmpty()) {
            db
                .insertInto(ITEM_TERM, ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID)
                .select(DSL
                    .select(DSL.val(itemId), TERM.ID)
                    .from(TERM)
                    .where(TERM.ID.in(ids), TERM.TAXONOMY.eq(taxonomy))
                )
                .execute();
        }
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
