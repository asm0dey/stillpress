package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.content.Slugs;
import site.asm0dey.stillpress.db.tables.pojos.Term;

@ApplicationScoped
public class TermRepository {
    private final DSLContext db;

    TermRepository(DSLContext db) {
        this.db = db;
    }

    public List<Term> forItem(long itemId) {
        return db
            .select(TERM.fields())
            .from(TERM)
            .join(ITEM_TERM)
            .on(ITEM_TERM.TERM_ID.eq(TERM.ID))
            .where(ITEM_TERM.ITEM_ID.eq(itemId))
            .orderBy(TERM.TAXONOMY, TERM.NAME)
            .fetch(r -> r.into(TERM).into(Term.class));
    }

    public Optional<Term> findById(long id) {
        return db.selectFrom(TERM).where(TERM.ID.eq(id)).fetchOptionalInto(Term.class);
    }

    public Optional<Term> findBySlug(String taxonomy, String slug) {
        return db
            .selectFrom(TERM)
            .where(TERM.TAXONOMY.eq(taxonomy), TERM.SLUG.eq(slug))
            .fetchOptionalInto(Term.class);
    }

    /**
     * A new term; its slug is {@code Slugs.title(name)}, moved on to {@code -2}, {@code -3}... when the taxonomy
     * already has it.
     */
    public Term create(String taxonomy, String name, Long parentId) {
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
        return r.into(Term.class);
    }

    public List<Term> byTaxonomy(String taxonomy) {
        return db.selectFrom(TERM).where(TERM.TAXONOMY.eq(taxonomy)).orderBy(TERM.NAME).fetchInto(Term.class);
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

    /**
     * The posts among {@code itemIds} without a category.
     */
    public List<Long> uncategorizedPosts(Collection<Long> itemIds) {
        return db
            .select(ITEM.ID)
            .from(ITEM)
            .where(
                    ITEM.ID.in(itemIds),
                    ITEM.TYPE.eq("post"),
                    DSL.notExists(DSL
                        .selectOne()
                        .from(ITEM_TERM)
                        .join(TERM)
                        .on(TERM.ID.eq(ITEM_TERM.TERM_ID))
                        .where(ITEM_TERM.ITEM_ID.eq(ITEM.ID), TERM.TAXONOMY.eq("category"))
                    )
            )
            .fetch(ITEM.ID);
    }

    /**
     * Gives every item in {@code itemIds} the term, in one statement.
     */
    public void addToItems(Collection<Long> itemIds, long termId) {
        db
            .insertInto(ITEM_TERM, ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID)
            .select(DSL.select(ITEM.ID, DSL.val(termId)).from(ITEM).where(ITEM.ID.in(itemIds)))
            .execute();
    }

    public List<Term> all() {
        return db.selectFrom(TERM).fetchInto(Term.class);
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

    /**
     * A term with the number of its published items, as the back office lists them.
     */
    public record Counted(long id, String name, String slug, Long parentId, String description, int count) {}

    public List<Counted> byTaxonomyCounted(String taxonomy) {
        return db
            .select(TERM.ID, TERM.NAME, TERM.SLUG, TERM.PARENT_ID, TERM.DESCRIPTION, DSL.count(ITEM.ID))
            .from(TERM)
            .leftJoin(ITEM_TERM)
            .on(ITEM_TERM.TERM_ID.eq(TERM.ID))
            .leftJoin(ITEM)
            .on(ITEM.ID.eq(ITEM_TERM.ITEM_ID), ITEM.STATUS.eq("publish"))
            .where(TERM.TAXONOMY.eq(taxonomy))
            .groupBy(TERM.ID, TERM.NAME, TERM.SLUG, TERM.PARENT_ID, TERM.DESCRIPTION)
            .orderBy(TERM.NAME)
            .fetch(Records.mapping(Counted::new));
    }

    public void update(long id, String name, String slug, Long parentId, String description) {
        db
            .update(TERM)
            .set(TERM.NAME, name)
            .set(TERM.SLUG, slug)
            .set(TERM.PARENT_ID, parentId)
            .set(TERM.DESCRIPTION, description)
            .where(TERM.ID.eq(id))
            .execute();
    }

    /**
     * Removes the term from its items, hands its children to {@code parentId} (its own parent) and deletes it.
     */
    public void delete(long id, Long parentId) {
        db.deleteFrom(ITEM_TERM).where(ITEM_TERM.TERM_ID.eq(id)).execute();
        db.update(TERM).set(TERM.PARENT_ID, parentId).where(TERM.PARENT_ID.eq(id)).execute();
        db.deleteFrom(TERM).where(TERM.ID.eq(id)).execute();
    }

    /**
     * Ids of the items that have any of {@code termIds}; only published ones when {@code published}.
     */
    public List<Long> itemIds(Collection<Long> termIds, boolean published) {
        return db
            .selectDistinct(ITEM.ID)
            .from(ITEM)
            .join(ITEM_TERM)
            .on(ITEM_TERM.ITEM_ID.eq(ITEM.ID))
            .where(ITEM_TERM.TERM_ID.in(termIds), published ? ITEM.STATUS.eq("publish") : DSL.noCondition())
            .fetch(ITEM.ID);
    }
}
