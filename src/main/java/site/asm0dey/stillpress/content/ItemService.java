package site.asm0dey.stillpress.content;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Item rules shared by the editor and the background jobs: statuses, capabilities, permalinks, revisions and what a
 * save enqueues for publishing.
 */
@ApplicationScoped
public class ItemService {
    private static final Set<String> STATUSES = Set.of("draft", "pending", "future", "publish", "private");
    @Inject
    DSLContext db;
    @Inject
    SettingRepository settings;
    @Inject
    ItemRepository items;
    @Inject
    TermRepository terms;
    @Inject
    AuthorRepository authors;
    @Inject
    RedirectRepository redirects;
    @Inject
    FragmentRepository fragments;
    @Inject
    Revisions revisions;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;
    @Inject
    ImportGuard guard;

    public record Actor(long userId, Role role) {}

    /**
     * An editor save; a null field keeps the stored value. {@code slug} {@code ""} clears it, so the next permalink
     * comes from the title. {@code localDate} is in the site's time zone.
     */
    public record Update(
            String title,
            String slug,
            String body,
            String bodyFormat,
            String excerpt,
            String status,
            LocalDateTime localDate,
            List<Long> categories,
            List<Long> tags,
            Long parentId,
            Long authorId,
            String commentStatus,
            Integer menuOrder,
            LocalDateTime expectedModifiedAt
    ) {}

    /**
     * A refused request: 400 (with the offending {@code field}), 403, 404 or 409.
     */
    public static final class Refused extends RuntimeException {
        public final int status;
        public final String error;
        public final String field;

        public Refused(int status, String error, String field) {
            super(error);
            this.status = status;
            this.error = error;
            this.field = field;
        }
    }

    @Transactional
    public ItemRecord create(String type, long authorId) {
        guard.close();
        // a concurrent create may take the same public id; the second try sees it
        return insertNew(type, authorId)
            .or(() -> insertNew(type, authorId))
            .orElseThrow();
    }

    /**
     * WordPress-style public id: one past the highest, imported ids included, computed in the insert itself.
     */
    private Optional<ItemRecord> insertNew(String type, long authorId) {
        var next = coalesce(max(ITEM.SOURCE_ID), 0L).plus(1);
        var home = settings.get("home_url").orElse("").replaceAll("/+$", "");
        var commentStatus = settings.get("default_comment_status").orElse("open");
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
                    val(now()),
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
            .fetchOptional();
    }

    /**
     * Saves an editor's changes; see {@link Update}. Refused with 409 when the item changed since
     * {@code expectedModifiedAt}, 403 past the actor's capabilities, 400 for invalid values.
     */
    @Transactional
    public ItemRecord update(long id, Update u, Actor a) {
        guard.close();
        var r = items
            .lock(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        if (!Objects.equals(r.getModifiedAt(), u.expectedModifiedAt())) {
            throw new Refused(409, "conflict", null);
        }
        if (!Capabilities.canEdit(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        var wasPublished = "publish".equals(r.getStatus());
        var now = now();
        var dateChanged = u.localDate() != null && !u.localDate().equals(localDate(r));
        if (dateChanged) {
            r.setPublishedLocal(u.localDate());
            r.setPublishedAt(u.localDate().atZone(zone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        var status = Objects.requireNonNullElse(u.status(), r.getStatus());
        if (!STATUSES.contains(status)) {
            throw invalid("status");
        }
        if ("publish".equals(status) && r.getPublishedAt() == null) {
            r.setPublishedAt(now);
            r.setPublishedLocal(localDate(r));
        }
        var future = r.getPublishedAt() != null && r.getPublishedAt().isAfter(now);
        if ("publish".equals(status) && future) {
            status = "future";
        } else if ("future".equals(status) && !future) {
            throw invalid("date");
        }
        if (!Capabilities.canSetStatus(a.role(), a.userId(), r, status)) {
            throw forbidden();
        }
        if (u.authorId() != null && !u.authorId().equals(r.getAuthorId())) {
            if (!Capabilities.canReassign(a.role())) {
                throw forbidden();
            }
            if (authors.findById(u.authorId()).isEmpty()) {
                throw invalid("authorId");
            }
            r.setAuthorId(u.authorId());
        }
        if (u.commentStatus() != null && !Set.of("open", "closed").contains(u.commentStatus())) {
            throw invalid("commentStatus");
        }
        if (u.bodyFormat() != null && !Set.of("blocks", "classic").contains(u.bodyFormat())) {
            throw invalid("bodyFormat");
        }
        var parentChanged =
                "page".equals(r.getType()) && !Objects.equals(u.parentId(), r.getParentId()) && u.parentId() != null;
        if (parentChanged
                && (u.parentId() == id || items.find(u.parentId()).filter(p -> "page".equals(p.getType())).isEmpty())) {
            throw invalid("parentId");
        }
        Optional.ofNullable(u.title()).ifPresent(r::setTitle);
        Optional.ofNullable(u.body()).ifPresent(r::setBody);
        Optional.ofNullable(u.bodyFormat()).ifPresent(r::setBodyFormat);
        Optional.ofNullable(u.excerpt()).ifPresent(r::setExcerpt);
        Optional.ofNullable(u.commentStatus()).ifPresent(r::setCommentStatus);
        Optional.ofNullable(u.menuOrder()).ifPresent(r::setMenuOrder);
        if (parentChanged) {
            r.setParentId(u.parentId());
        }
        var slugChanged = u.slug() != null && !u.slug().equals(r.getSlug());
        if (slugChanged) {
            var base = Slugs.title(u.slug());
            r.setSlug(base.isEmpty() ? null : uniqueSlug(r, base));
        }
        if (u.categories() != null) {
            terms.setForItem(id, "category", u.categories());
        }
        if (u.tags() != null) {
            terms.setForItem(id, "post_tag", u.tags());
        }
        r.setStatus(status);
        // the permalink is fixed at first publish and moves only with the slug, date or parent
        if ("publish".equals(status) && (r.getPermalink() == null || slugChanged || dateChanged || parentChanged)) {
            assignPermalink(r);
        }
        r.setModifiedAt(now);
        r.update();
        revisions.saved("item", id, r.getTitle(), r.getBody(), r.getBodyFormat(), r.getExcerpt(), a.userId());
        revisions.deleteAutosave("item", id, a.userId());
        fragments.delete(List.of(id));
        if ("publish".equals(status) || wasPublished) {
            queue.item(id);
            jobs.requestPublish();
        }
        if ("future".equals(status)) {
            jobs.scheduleGoLive(id, r.getPublishedAt().toInstant(ZoneOffset.UTC));
        }
        return r;
    }

    /**
     * An ordinary {@link #update} with the revision's title, body and excerpt and the item's current status.
     */
    @Transactional
    public ItemRecord restoreRevision(long id, long revisionId, Actor a) {
        var rev = revisions
            .find(revisionId)
            .filter(v -> "item".equals(v.getObjectType()) && v.getObjectId() == id && !v.getAutosave())
            .orElseThrow(() -> new Refused(404, "not_found", null));
        var r = items
            .find(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        return update(
                id,
                new Update(
                        rev.getTitle(),
                        null,
                        rev.getBody(),
                        rev.getBodyFormat(),
                        rev.getExcerpt(),
                        r.getStatus(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        r.getModifiedAt()
                ),
                a
        );
    }

    @Transactional
    public void autosave(long id, Actor a, String title, String body, String excerpt) {
        guard.close();
        var r = items
            .find(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        if (!Capabilities.canEdit(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        revisions.autosave("item", id, a.userId(), title, body, excerpt);
    }

    @Transactional
    public void trash(long id, Actor a) {
        setTrashed(id, a, true);
    }

    /**
     * Back to {@code draft}.
     */
    @Transactional
    public void restore(long id, Actor a) {
        setTrashed(id, a, false);
    }

    private void setTrashed(long id, Actor a, boolean trash) {
        guard.close();
        var r = items
            .lock(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        if (!Capabilities.canTrash(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        if (trash == "trash".equals(r.getStatus())) {
            throw invalid("status");
        }
        var wasPublished = "publish".equals(r.getStatus());
        r.setStatus(trash ? "trash" : "draft");
        r.setModifiedAt(now());
        r.update();
        if (wasPublished) {
            fragments.delete(List.of(id));
            queue.item(id);
            jobs.requestPublish();
        }
    }

    /**
     * A new category or tag; its empty archive is published.
     */
    @Transactional
    public TermRecord createTerm(String taxonomy, String name, Long parentId, Actor a) {
        if (!Capabilities.canCreateTerm(a.role())) {
            throw forbidden();
        }
        var category = "category".equals(taxonomy);
        if (!category && !"post_tag".equals(taxonomy)) {
            throw invalid("taxonomy");
        }
        if (name == null || Slugs.title(name).isEmpty()) {
            throw invalid("name");
        }
        if (parentId != null
                && (!category || terms.findById(parentId).filter(p -> "category".equals(p.getTaxonomy())).isEmpty())) {
            throw invalid("parentId");
        }
        guard.close();
        var t = terms.create(taxonomy, name.strip(), parentId);
        var base = settings.get(category ? "category_base" : "tag_base").orElse(category ? "category" : "tag");
        queue.base("/" + base + "/" + (category ? termPath(t.getId()) : t.getSlug()) + "/");
        jobs.requestPublish();
        return t;
    }

    /**
     * The item's date in the site's time zone, null when it has none.
     */
    public LocalDateTime localDate(ItemRecord r) {
        if (r.getPublishedLocal() != null) {
            return r.getPublishedLocal();
        }
        return r.getPublishedAt() == null
                ? null
                : r.getPublishedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(zone()).toLocalDateTime();
    }

    /**
     * Gives {@code r} its slug and permalink as WordPress would: posts from the site's {@code permalink_structure},
     * pages from their ancestors' slugs. The slug is made unique among items of the same type (and the permalink
     * among all) with {@code -2}, {@code -3}... The caller stores the record; the redirects are written here: on the
     * first permalink the shortlinks {@code ?p=} (and {@code ?page_id=} for pages), on a change old path to new.
     */
    public void assignPermalink(ItemRecord r) {
        var old = r.getPermalink();
        var base = r.getSlug() == null || r.getSlug().isBlank()
                ? Slugs.title(Objects.requireNonNullElse(r.getTitle(), ""))
                : r.getSlug();
        if (base.isEmpty()) {
            base = String.valueOf(r.getSourceId());
        }
        var slug = base;
        var permalink = permalink(r, slug);
        for (int n = 2; slugTaken(r, slug) || taken(permalink, r.getId()); n++) {
            slug = base + "-" + n;
            permalink = permalink(r, slug);
        }
        r.setSlug(slug);
        r.setPermalink(permalink);
        if (old == null && r.getSourceId() != null) {
            redirects.put("?p=" + r.getSourceId(), permalink);
            if ("page".equals(r.getType())) {
                redirects.put("?page_id=" + r.getSourceId(), permalink);
            }
        } else if (old != null && !old.equals(permalink)) {
            redirects.put(old, permalink);
        }
    }

    private String uniqueSlug(ItemRecord r, String base) {
        var slug = base;
        for (int n = 2; slugTaken(r, slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    /**
     * WordPress's {@code wp_unique_post_slug}: unique among items of the same type, whatever their date.
     */
    private boolean slugTaken(ItemRecord r, String slug) {
        return db.fetchExists(
                ITEM,
                ITEM.TYPE.eq(r.getType()),
                ITEM.SLUG.eq(slug),
                r.getId() == null ? ITEM.ID.isNotNull() : ITEM.ID.ne(r.getId())
        );
    }

    private boolean taken(String permalink, Long id) {
        return db.fetchExists(ITEM, ITEM.PERMALINK.eq(permalink), id == null ? ITEM.ID.isNotNull() : ITEM.ID.ne(id));
    }

    private String permalink(ItemRecord r, String slug) {
        if ("page".equals(r.getType())) {
            var parts = new ArrayDeque<String>();
            parts.addFirst(slug);
            var seen = new HashSet<Long>();
            for (var p = r.getParentId(); p != null && seen.add(p); ) {
                var parent = db.select(ITEM.SLUG, ITEM.PARENT_ID).from(ITEM).where(ITEM.ID.eq(p)).fetchOne();
                if (parent == null) {
                    break;
                }
                parts.addFirst(parent.get(ITEM.SLUG));
                p = parent.get(ITEM.PARENT_ID);
            }
            return "/" + String.join("/", parts) + "/";
        }
        var structure = settings.get("permalink_structure").orElse(PermalinkStructure.DEFAULT);
        var local = Objects.requireNonNullElseGet(localDate(r), () -> LocalDateTime.now(zone()));
        var author = r.getAuthorId() == null
                ? null
                : db.select(AUTHOR.SLUG).from(AUTHOR).where(AUTHOR.ID.eq(r.getAuthorId())).fetchOne(AUTHOR.SLUG);
        return PermalinkStructure.render(
                structure,
                new PermalinkStructure.Fields(
                        Objects.requireNonNullElse(r.getSourceId(), 0L),
                        slug,
                        local,
                        categoryPath(r.getId()),
                        Objects.requireNonNullElse(author, ""),
                        null
                )
        );
    }

    private ZoneId zone() {
        return ZoneId.of(settings.get("timezone").orElse("UTC"));
    }

    /**
     * UTC, at the precision the database keeps, so a value read back equals the one written.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Refused forbidden() {
        return new Refused(403, "forbidden", null);
    }

    private static Refused invalid(String field) {
        return new Refused(400, "invalid", field);
    }

    /**
     * WordPress's {@code %category%}: the category with the lowest id, under its ancestors; {@code uncategorized}
     * when the post has none.
     */
    private String categoryPath(Long itemId) {
        var first = itemId == null
                ? null
                : db
            .select(TERM.ID)
            .from(TERM)
            .join(ITEM_TERM)
            .on(ITEM_TERM.TERM_ID.eq(TERM.ID))
            .where(ITEM_TERM.ITEM_ID.eq(itemId), TERM.TAXONOMY.eq("category"))
            .orderBy(TERM.ID)
            .limit(1)
            .fetchOne(TERM.ID);
        return first == null ? "uncategorized" : termPath(first);
    }

    /**
     * The term's slug under its ancestors' slugs.
     */
    private String termPath(long termId) {
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (Long t = termId; t != null && seen.add(t); ) {
            var term = db.select(TERM.SLUG, TERM.PARENT_ID).from(TERM).where(TERM.ID.eq(t)).fetchOne();
            if (term == null) {
                break;
            }
            parts.addFirst(term.get(TERM.SLUG));
            t = term.get(TERM.PARENT_ID);
        }
        return String.join("/", parts);
    }
}
