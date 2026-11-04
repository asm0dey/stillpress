package site.asm0dey.stillpress.content;

import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ItemRepository;
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
    Permalinker permalinks;
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
        var next = DSL.<Long>coalesce(max(ITEM.SOURCE_ID), 0L).plus(1);
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
        var dateChanged = u.localDate() != null && !u.localDate().equals(permalinks.localDate(r));
        if (dateChanged) {
            r.setPublishedLocal(u.localDate());
            r.setPublishedAt(u
                .localDate()
                .atZone(permalinks.zone())
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime()
            );
        }
        var status = Objects.requireNonNullElse(u.status(), r.getStatus());
        if (!STATUSES.contains(status)) {
            throw invalid("status");
        }
        if ("publish".equals(status) && r.getPublishedAt() == null) {
            r.setPublishedAt(now);
            r.setPublishedLocal(permalinks.localDate(r));
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
        var authorChanged = u.authorId() != null && !u.authorId().equals(r.getAuthorId());
        if (authorChanged) {
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
            r.setSlug(base.isEmpty() ? null : permalinks.uniqueSlug(r, base));
        }
        var categoriesChanged = false;
        if (u.categories() != null) {
            var before = terms.idsForItem(id, "category");
            terms.setForItem(id, "category", u.categories());
            categoriesChanged = !before.equals(terms.idsForItem(id, "category"));
        }
        if (u.tags() != null) {
            terms.setForItem(id, "post_tag", u.tags());
        }
        r.setStatus(status);
        // the permalink is fixed at first publish and moves only with what it is built from
        var pathChanged = slugChanged
                || dateChanged
                || parentChanged
                || categoriesChanged && permalinks.uses("%category%")
                || authorChanged && permalinks.uses("%author%");
        if ("publish".equals(status) && (r.getPermalink() == null || pathChanged)) {
            permalinks.assignPermalink(r);
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
        // the lock keeps two autosaves of one user from both inserting
        var r = items
            .lock(id)
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
}
