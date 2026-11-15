package site.asm0dey.stillpress.store;

import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;
import static site.asm0dey.stillpress.db.Tables.*;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.content.PermalinkStructure;
import site.asm0dey.stillpress.db.tables.pojos.Item;

@ApplicationScoped
public class ItemRepository {
    private final DSLContext db;

    ItemRepository(DSLContext db) {
        this.db = db;
    }

    /**
     * Published, non-password-protected posts and pages, newest first.
     */
    public List<Item> findPublished() {
        return db
            .selectFrom(ITEM)
            .where(ITEM.STATUS.eq("publish"), ITEM.TYPE.in("post", "page"), ITEM.PASSWORD_PROTECTED.isFalse())
            .orderBy(ITEM.PUBLISHED_AT.desc())
            .fetchInto(Item.class);
    }

    /**
     * Ids of the published items by {@code authorId}.
     */
    public List<Long> publishedIdsBy(long authorId) {
        return db
            .select(ITEM.ID)
            .from(ITEM)
            .where(ITEM.AUTHOR_ID.eq(authorId), ITEM.STATUS.eq("publish"))
            .fetch(ITEM.ID);
    }

    public Optional<Item> findByPermalink(String decodedPath) {
        return db.selectFrom(ITEM).where(ITEM.PERMALINK.eq(decodedPath)).fetchOptionalInto(Item.class);
    }

    public int count() {
        return db.fetchCount(ITEM);
    }

    /**
     * Posts and pages outside the trash, as the Posts and Pages screens count them (attachments are items too).
     */
    public int countPostsAndPages() {
        return db.fetchCount(ITEM, ITEM.TYPE.in("post", "page"), ITEM.STATUS.ne("trash"));
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
                    DSL.<LocalDateTime>coalesce(ITEM.PUBLISHED_LOCAL, ITEM.PUBLISHED_AT),
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
    public List<Item> findByIds(List<Long> ids) {
        var byId = new HashMap<Long, Item>();
        db
            .selectFrom(ITEM)
            .where(ITEM.ID.in(ids))
            .fetchInto(Item.class)
            .forEach(r -> byId.put(r.id(), r));
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
                    DSL.<String>coalesce(ITEM.TITLE, ""),
                    ITEM.STATUS,
                    DSL.<String>coalesce(AUTHOR.DISPLAY_NAME, AUTHOR.LOGIN),
                    DSL.<LocalDateTime>coalesce(ITEM.PUBLISHED_LOCAL, ITEM.PUBLISHED_AT),
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
    public Optional<Item> lock(long id) {
        return db.selectFrom(ITEM).where(ITEM.ID.eq(id)).forUpdate().fetchOptionalInto(Item.class);
    }

    public Optional<Item> find(long id) {
        return db.selectFrom(ITEM).where(ITEM.ID.eq(id)).fetchOptionalInto(Item.class);
    }

    /**
     * A new draft of {@code type} with the next public id; {@code home} prefixes its {@code ?p=} guid.
     */
    public Item create(String type, long authorId, String home, String commentStatus, LocalDateTime now) {
        return PublicIds.insertUntilFree(() -> insertNew(type, authorId, home, commentStatus, now));
    }

    /**
     * WordPress-style public id: one past the highest, imported ids included, computed in the insert itself.
     */
    private Optional<Item> insertNew(String type, long authorId, String home, String commentStatus, LocalDateTime now) {
        var next = DSL.<Long>coalesce(max(ITEM.SOURCE_ID), 0L).plus(1);
        return db
            .insertInto(
                    ITEM,
                    ITEM.TYPE,
                    ITEM.STATUS,
                    ITEM.TITLE,
                    ITEM.BODY,
                    ITEM.BODY_FORMAT,
                    ITEM.EXCERPT,
                    ITEM.AUTHOR_ID,
                    ITEM.MODIFIED_AT,
                    ITEM.COMMENT_STATUS,
                    ITEM.MENU_ORDER,
                    ITEM.SOURCE_ID,
                    ITEM.GUID
            )
            .select(select(
                    val(type),
                    val("draft"),
                    val(""),
                    val(""),
                    val("blocks"),
                    val(""),
                    val(authorId),
                    val(now),
                    val(commentStatus),
                    val(0),
                    next,
                    concat(val(home + "/?p="), next.cast(String.class))
            )
                .from(ITEM)
            )
            .onConflict(ITEM.SOURCE_ID)
            .doNothing()
            .returning()
            .fetchOptionalInto(Item.class);
    }

    /**
     * What an editor's save writes; the permalink is set apart, by {@link #setPath}.
     */
    public record Edit(
            String status,
            String title,
            String slug,
            String body,
            String bodyFormat,
            String excerpt,
            Long authorId,
            Long parentId,
            LocalDateTime publishedAt,
            LocalDateTime publishedLocal,
            String commentStatus,
            Integer menuOrder,
            LocalDateTime modifiedAt
    ) {}

    public Item update(long id, Edit e) {
        return db
            .update(ITEM)
            .set(ITEM.STATUS, e.status())
            .set(ITEM.TITLE, e.title())
            .set(ITEM.SLUG, e.slug())
            .set(ITEM.BODY, e.body())
            .set(ITEM.BODY_FORMAT, e.bodyFormat())
            .set(ITEM.EXCERPT, e.excerpt())
            .set(ITEM.AUTHOR_ID, e.authorId())
            .set(ITEM.PARENT_ID, e.parentId())
            .set(ITEM.PUBLISHED_AT, e.publishedAt())
            .set(ITEM.PUBLISHED_LOCAL, e.publishedLocal())
            .set(ITEM.COMMENT_STATUS, e.commentStatus())
            .set(ITEM.MENU_ORDER, e.menuOrder())
            .set(ITEM.MODIFIED_AT, e.modifiedAt())
            .where(ITEM.ID.eq(id))
            .returning()
            .fetchSingleInto(Item.class);
    }

    public Item setPath(long id, String slug, String permalink) {
        return db
            .update(ITEM)
            .set(ITEM.SLUG, slug)
            .set(ITEM.PERMALINK, permalink)
            .where(ITEM.ID.eq(id))
            .returning()
            .fetchSingleInto(Item.class);
    }

    public void setStatus(long id, String status, LocalDateTime modifiedAt) {
        db.update(ITEM).set(ITEM.STATUS, status).set(ITEM.MODIFIED_AT, modifiedAt).where(ITEM.ID.eq(id)).execute();
    }

    /**
     * Publishes a scheduled item that is still due at {@code now}; false when it is not (rescheduled or unscheduled).
     */
    public boolean publishIfDue(long id, LocalDateTime now) {
        return db
            .update(ITEM)
            .set(ITEM.STATUS, "publish")
            .where(ITEM.ID.eq(id), ITEM.STATUS.eq("future"), ITEM.PUBLISHED_AT.le(now))
            .execute() == 1;
    }

    /**
     * WordPress's {@code wp_unique_post_slug}: unique among items of the same type, whatever their date.
     */
    public boolean slugTaken(String type, String slug, Long exceptId) {
        return db.fetchExists(ITEM, ITEM.TYPE.eq(type), ITEM.SLUG.eq(slug), other(exceptId));
    }

    public boolean permalinkTaken(String permalink, Long exceptId) {
        return db.fetchExists(ITEM, ITEM.PERMALINK.eq(permalink), other(exceptId));
    }

    private static Condition other(Long exceptId) {
        return exceptId == null ? ITEM.ID.isNotNull() : ITEM.ID.ne(exceptId);
    }

    /**
     * An imported item with its WordPress post id; returns its id. The parent is set apart, by {@link #setParent}.
     */
    public long insertImported(Item i) {
        var r = db.newRecord(ITEM);
        r.setSourceId(i.sourceId());
        r.setType(i.type());
        r.setStatus(i.status());
        r.setPasswordProtected(i.passwordProtected());
        r.setTitle(i.title());
        r.setSlug(i.slug());
        r.setPermalink(i.permalink());
        r.setBody(i.body());
        r.setBodyFormat(i.bodyFormat());
        r.setExcerpt(i.excerpt());
        r.setAuthorId(i.authorId());
        r.setPublishedAt(i.publishedAt());
        r.setPublishedLocal(i.publishedLocal());
        r.setModifiedAt(i.modifiedAt());
        r.setGuid(i.guid());
        r.setCommentStatus(i.commentStatus());
        r.setMenuOrder(i.menuOrder());
        r.setMimeType(i.mimeType());
        r.store();
        return r.getId();
    }

    public void setParent(long id, long parentId) {
        db.update(ITEM).set(ITEM.PARENT_ID, parentId).where(ITEM.ID.eq(id)).execute();
    }

    /**
     * What an import needs of an item's parent.
     */
    public record Parent(Long id, String permalink, String status, Boolean passwordProtected) {}

    public Optional<Parent> findParent(long sourceId) {
        return db
            .select(ITEM.ID, ITEM.PERMALINK, ITEM.STATUS, ITEM.PASSWORD_PROTECTED)
            .from(ITEM)
            .where(ITEM.SOURCE_ID.eq(sourceId))
            .fetchOptional(Records.mapping(Parent::new));
    }

    private record TermRow(Long id, String slug, Long parentId) {}

    /**
     * The published posts with their category (the lowest id, as WordPress picks it) and author.
     */
    public List<PermalinkStructure.Fields> publishedPostFields() {
        var categories = db
            .select(TERM.ID, TERM.SLUG, TERM.PARENT_ID)
            .from(TERM)
            .where(TERM.TAXONOMY.eq("category"))
            .fetchMap(TERM.ID, Records.mapping(TermRow::new));
        var firstCategory = db
            .select(ITEM_TERM.ITEM_ID, DSL.min(ITEM_TERM.TERM_ID))
            .from(ITEM_TERM)
            .join(TERM)
            .on(TERM.ID.eq(ITEM_TERM.TERM_ID))
            .where(TERM.TAXONOMY.eq("category"))
            .groupBy(ITEM_TERM.ITEM_ID)
            .fetchMap(ITEM_TERM.ITEM_ID, DSL.min(ITEM_TERM.TERM_ID));
        return db
            .select(ITEM.ID, ITEM.SOURCE_ID, ITEM.SLUG, ITEM.PUBLISHED_LOCAL, AUTHOR.SLUG, ITEM.PERMALINK)
            .from(ITEM)
            .leftJoin(AUTHOR)
            .on(AUTHOR.ID.eq(ITEM.AUTHOR_ID))
            .where(ITEM.TYPE.eq("post"), ITEM.STATUS.eq("publish"), ITEM.PUBLISHED_LOCAL.isNotNull())
            .fetch(Records.mapping((id, sourceId, slug, publishedLocal, authorSlug, permalink) -> {
                var path = new ArrayDeque<String>();
                for (var t = categories.get(firstCategory.get(id)); t != null; t = categories.get(t.parentId())) {
                    path.addFirst(t.slug());
                }
                return new PermalinkStructure.Fields(
                        Objects.requireNonNullElse(sourceId, 0L),
                        Objects.requireNonNullElse(slug, ""),
                        publishedLocal,
                        String.join("/", path),
                        Objects.requireNonNullElse(authorSlug, ""),
                        permalink
                );
            }));
    }
}
