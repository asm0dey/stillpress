package site.asm0dey.stillpress.store;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static site.asm0dey.stillpress.db.Tables.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;

@ApplicationScoped
public class ItemRepository {
    @Inject
    DSLContext db;

    /**
     * Published, non-password-protected posts and pages, newest first.
     */
    public List<ItemRecord> findPublished() {
        return db
            .selectFrom(ITEM)
            .where(ITEM.STATUS.eq("publish"), ITEM.TYPE.in("post", "page"), ITEM.PASSWORD_PROTECTED.isFalse())
            .orderBy(ITEM.PUBLISHED_AT.desc())
            .fetch();
    }

    public Optional<ItemRecord> findByPermalink(String decodedPath) {
        return db.selectFrom(ITEM).where(ITEM.PERMALINK.eq(decodedPath)).fetchOptional();
    }

    public int count() {
        return db.fetchCount(ITEM);
    }

    /**
     * No items, comments, media, terms or reusable blocks: nothing an import would collide with.
     */
    public boolean contentEmpty() {
        return db.fetchValue(
                field(notExists(selectOne().from(ITEM))
                    .and(notExists(selectOne().from(COMMENT)))
                    .and(notExists(selectOne().from(MEDIA)))
                    .and(notExists(selectOne().from(TERM)))
                    .and(notExists(selectOne().from(REUSABLE_BLOCK)))
                )
        );
    }

    /**
     * An item without its body: enough to place it in archives and feeds without holding content in memory.
     * {@code publishedAt} is UTC (feeds); {@code publishedLocal} is the site-local time (date archives), UTC when the
     * export had no {@code wp:post_date}.
     */
    public record Summary(
            long id,
            String type,
            String permalink,
            LocalDateTime publishedAt,
            LocalDateTime publishedLocal,
            Long authorId
    ) {}

    /**
     * Published, non-password-protected posts and pages that have a permalink, newest first (undated last).
     */
    public List<Summary> findPublishedSummaries() {
        return db
            .select(
                    ITEM.ID,
                    ITEM.TYPE,
                    ITEM.PERMALINK,
                    ITEM.PUBLISHED_AT,
                    DSL.coalesce(ITEM.PUBLISHED_LOCAL, ITEM.PUBLISHED_AT),
                    ITEM.AUTHOR_ID
            )
            .from(ITEM)
            .where(
                    ITEM.STATUS.eq("publish"),
                    ITEM.TYPE.in("post", "page"),
                    ITEM.PASSWORD_PROTECTED.isFalse(),
                    ITEM.PERMALINK.isNotNull()
            )
            .orderBy(ITEM.PUBLISHED_AT.desc().nullsLast(), ITEM.ID.desc())
            .fetch(Records.mapping(Summary::new));
    }

    /**
     * Full records for the given ids, in the order of {@code ids}.
     */
    public List<ItemRecord> findByIds(List<Long> ids) {
        var byId = new HashMap<Long, ItemRecord>();
        db
            .selectFrom(ITEM)
            .where(ITEM.ID.in(ids))
            .forEach(r -> byId.put(r.getId(), r));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * One line of the admin's item list.
     */
    public record Row(
            long id,
            String type,
            String title,
            String status,
            String author,
            LocalDateTime date,
            LocalDateTime modified,
            String permalink
    ) {}

    /**
     * What the admin list shows: items of {@code type}, optionally of one author, matching {@code search} in the
     * title. {@code status} null means every status but {@code trash}.
     */
    public record Filter(String type, String status, Long authorId, String search) {
        Condition condition(boolean withStatus) {
            var c = ITEM.TYPE.eq(type);
            if (authorId != null) {
                c = c.and(ITEM.AUTHOR_ID.eq(authorId));
            }
            if (search != null && !search.isBlank()) {
                c = c.and(ITEM.TITLE.containsIgnoreCase(search));
            }
            if (withStatus) {
                c = c.and(status == null ? ITEM.STATUS.ne("trash") : ITEM.STATUS.eq(status));
            }
            return c;
        }
    }

    /**
     * Newest modified first.
     */
    public List<Row> list(Filter f, int offset, int limit) {
        return db
            .select(
                    ITEM.ID,
                    ITEM.TYPE,
                    DSL.coalesce(ITEM.TITLE, ""),
                    ITEM.STATUS,
                    DSL.coalesce(AUTHOR.DISPLAY_NAME, AUTHOR.LOGIN),
                    DSL.coalesce(ITEM.PUBLISHED_LOCAL, ITEM.PUBLISHED_AT),
                    ITEM.MODIFIED_AT,
                    ITEM.PERMALINK
            )
            .from(ITEM)
            .leftJoin(AUTHOR)
            .on(AUTHOR.ID.eq(ITEM.AUTHOR_ID))
            .where(f.condition(true))
            .orderBy(ITEM.MODIFIED_AT.desc().nullsLast(), ITEM.ID.desc())
            .offset(offset)
            .limit(limit)
            .fetch(Records.mapping(Row::new));
    }

    public int count(Filter f) {
        return db.fetchCount(ITEM, f.condition(true));
    }

    /**
     * Items per status under the filter's type, author and search, in one {@code GROUP BY}.
     */
    public Map<String, Integer> countsByStatus(Filter f) {
        var n = DSL.count();
        return db
            .select(ITEM.STATUS, n)
            .from(ITEM)
            .where(f.condition(false))
            .groupBy(ITEM.STATUS)
            .fetchMap(ITEM.STATUS, n);
    }

    /**
     * Locks the row for the rest of the transaction, so concurrent saves of one item run one after the other.
     */
    public Optional<ItemRecord> lock(long id) {
        return db.selectFrom(ITEM).where(ITEM.ID.eq(id)).forUpdate().fetchOptional();
    }

    public Optional<ItemRecord> find(long id) {
        return db.selectFrom(ITEM).where(ITEM.ID.eq(id)).fetchOptional();
    }
}
