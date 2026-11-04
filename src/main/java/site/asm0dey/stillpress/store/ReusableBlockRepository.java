package site.asm0dey.stillpress.store;

import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.blocks.Block;
import site.asm0dey.stillpress.blocks.BlockParser;
import site.asm0dey.stillpress.db.tables.records.ReusableBlockRecord;

/**
 * Reusable blocks, addressed everywhere (REST, {@code wp:block {"ref":N}}) by their WordPress post id, {@code source_id}.
 */
@ApplicationScoped
public class ReusableBlockRepository {
    @Inject
    DSLContext db;

    /**
     * An item (not trashed) whose body uses some of the asked-for refs, confirmed by parsing.
     */
    public record Use(long itemId, String status, Set<Long> refs) {}

    /**
     * Content for a {@code wp:block} ref; imported markup refers to the WordPress post id, i.e. {@code source_id}.
     */
    public Optional<String> content(long id) {
        return db
            .select(REUSABLE_BLOCK.CONTENT)
            .from(REUSABLE_BLOCK)
            .where(REUSABLE_BLOCK.SOURCE_ID.eq(id))
            .fetchOptional(REUSABLE_BLOCK.CONTENT);
    }

    public List<ReusableBlockRecord> all() {
        return db.selectFrom(REUSABLE_BLOCK).orderBy(REUSABLE_BLOCK.SOURCE_ID).fetch();
    }

    public Optional<ReusableBlockRecord> find(long ref) {
        return db.selectFrom(REUSABLE_BLOCK).where(REUSABLE_BLOCK.SOURCE_ID.eq(ref)).fetchOptional();
    }

    /**
     * Locks the row for the rest of the transaction, so concurrent saves of one block run one after the other.
     */
    public Optional<ReusableBlockRecord> lock(long ref) {
        return db.selectFrom(REUSABLE_BLOCK).where(REUSABLE_BLOCK.SOURCE_ID.eq(ref)).forUpdate().fetchOptional();
    }

    public ReusableBlockRecord create(String title, String content, long authorId, LocalDateTime now) {
        // a concurrent create may take the same public id; the second try sees it
        return insertNew(title, content, authorId, now)
            .or(() -> insertNew(title, content, authorId, now))
            .orElseThrow();
    }

    /**
     * WordPress-style public id: one past the highest, imported ids included, computed in the insert itself.
     */
    private Optional<ReusableBlockRecord> insertNew(String title, String content, long authorId, LocalDateTime now) {
        var next = DSL.<Long>coalesce(max(REUSABLE_BLOCK.SOURCE_ID), 0L).plus(1);
        return db
            .insertInto(
                    REUSABLE_BLOCK,
                    REUSABLE_BLOCK.SOURCE_ID,
                    REUSABLE_BLOCK.TITLE,
                    REUSABLE_BLOCK.CONTENT,
                    REUSABLE_BLOCK.AUTHOR_ID,
                    REUSABLE_BLOCK.MODIFIED_AT
            )
            .select(select(next, val(title), val(content), val(authorId), val(now)).from(REUSABLE_BLOCK))
            .onConflict(REUSABLE_BLOCK.SOURCE_ID)
            .doNothing()
            .returning()
            .fetchOptional();
    }

    /**
     * Items using any of {@code refs}: a LIKE pre-filter in one query ({@code "ref":12} must not match
     * {@code "ref":123}), then each candidate body is parsed to confirm.
     */
    public List<Use> itemsUsing(Set<Long> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        var like = refs
            .stream()
            .flatMap(n -> Stream.of(ITEM.BODY.like("%\"ref\":%" + n + "}%"), ITEM.BODY.like("%\"ref\":%" + n + ",%")))
            .toList();
        return db
            .select(ITEM.ID, ITEM.STATUS, ITEM.BODY)
            .from(ITEM)
            .where(ITEM.STATUS.ne("trash"), DSL.or(like))
            .fetch()
            .stream()
            .map(r -> {
                var used = new HashSet<>(refsIn(r.get(ITEM.BODY)));
                used.retainAll(refs);
                return new Use(r.get(ITEM.ID), r.get(ITEM.STATUS), used);
            })
            .filter(u -> !u.refs().isEmpty())
            .toList();
    }

    /**
     * Every {@code wp:block} ref in a document, nested blocks included.
     */
    public static Set<Long> refsIn(String document) {
        var out = new HashSet<Long>();
        var todo = new ArrayDeque<>(BlockParser.parse(document));
        while (!todo.isEmpty()) {
            Block b = todo.pop();
            if ("core/block".equals(b.name()) && b.attrs() != null && b.attrs().path("ref").canConvertToLong()) {
                out.add(b.attrs().path("ref").asLong());
            }
            todo.addAll(b.innerBlocks());
        }
        return out;
    }
}
