package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Item;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.mail.ReviewNotifications;
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
    private final SettingRepository settings;
    private final ItemRepository items;
    private final TermRepository terms;
    private final AuthorRepository authors;
    private final Permalinker permalinks;
    private final FragmentRepository fragments;
    private final Revisions revisions;
    private final PublishQueue queue;
    private final Jobs jobs;
    private final ImportGuard guard;
    private final HtmlCheck html;
    private final ReviewNotifications reviewNotifications;

    ItemService(
            SettingRepository settings,
            ItemRepository items,
            TermRepository terms,
            AuthorRepository authors,
            Permalinker permalinks,
            FragmentRepository fragments,
            Revisions revisions,
            PublishQueue queue,
            Jobs jobs,
            ImportGuard guard,
            HtmlCheck html,
            ReviewNotifications reviewNotifications
    ) {
        this.settings = settings;
        this.items = items;
        this.terms = terms;
        this.authors = authors;
        this.permalinks = permalinks;
        this.fragments = fragments;
        this.revisions = revisions;
        this.queue = queue;
        this.jobs = jobs;
        this.guard = guard;
        this.html = html;
        this.reviewNotifications = reviewNotifications;
    }

    public record Actor(long userId, Role role) {}

    /**
     * An editor save; a null field keeps the stored value. {@code slug} {@code ""} clears it, so the next permalink
     * comes from the title, {@code parentId} {@code 0} clears the parent. {@code localDate} is in the site's time zone.
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
     * A refused request: 400 (with the offending {@code field}, and for {@code unsafe_html} the {@code violations}),
     * 403, 404 or 409.
     */
    public static final class Refused extends RuntimeException {
        public final int status;
        public final String error;
        public final String field;
        public final List<String> violations;

        public Refused(int status, String error, String field) {
            this(status, error, field, null);
        }

        public Refused(int status, String error, String field, List<String> violations) {
            super(error);
            this.status = status;
            this.error = error;
            this.field = field;
            this.violations = violations;
        }

        /**
         * The JSON error body: {@code error}, then {@code field} and {@code violations} when present.
         */
        public Map<String, Object> body() {
            var body = new LinkedHashMap<String, Object>();
            body.put("error", error);
            if (field != null) {
                body.put("field", field);
            }
            if (violations != null) {
                body.put("violations", violations);
            }
            return body;
        }
    }

    // the lookbehind keeps the regex linear
    @SuppressWarnings("java:S8786")
    @Transactional
    public Item create(String type, long authorId) {
        guard.close();
        var home = settings.get("home_url").orElse("").replaceAll("(?<!/)/+$", "");
        var commentStatus = settings.get("default_comment_status").orElse("open");
        return items.create(type, authorId, home, commentStatus, now());
    }

    /**
     * Saves an editor's changes; see {@link Update}. Refused with 409 when the item changed since
     * {@code expectedModifiedAt}, 403 past the actor's capabilities, 400 for invalid values.
     */
    @Transactional
    public Item update(long id, Update u, Actor a) {
        var r = items
            .lock(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        if (!Objects.equals(r.modifiedAt(), u.expectedModifiedAt())) {
            throw new Refused(409, "conflict", null);
        }
        if (!Capabilities.canEdit(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        // the state as stored after this save, so a status- or format-only change is judged too
        html.item(
                a.role(),
                or(u.title(), r.title()),
                or(u.body(), r.body()),
                or(u.bodyFormat(), r.bodyFormat()),
                or(u.excerpt(), r.excerpt())
        );
        guard.close();
        var wasPublished = "publish".equals(r.status());
        var wasPending = "pending".equals(r.status());
        var now = now();
        var publishedAt = r.publishedAt();
        var publishedLocal = r.publishedLocal();
        var dateChanged = u.localDate() != null && !u.localDate().equals(permalinks.localDate(r));
        if (dateChanged) {
            publishedLocal = u.localDate();
            publishedAt = u
                .localDate()
                .atZone(permalinks.zone())
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        }
        var status = Objects.requireNonNullElse(u.status(), r.status());
        if (!STATUSES.contains(status)) {
            throw invalid("status");
        }
        if ("publish".equals(status) && publishedAt == null) {
            publishedAt = now;
            publishedLocal = permalinks.localDate(publishedLocal, publishedAt);
        }
        var future = publishedAt != null && publishedAt.isAfter(now);
        if ("publish".equals(status) && future) {
            status = "future";
        } else if ("future".equals(status) && !future) {
            throw invalid("date");
        }
        if (!Capabilities.canSetStatus(a.role(), a.userId(), r, status)) {
            throw forbidden();
        }
        var authorChanged = u.authorId() != null && !u.authorId().equals(r.authorId());
        if (authorChanged) {
            if (!Capabilities.canReassign(a.role())) {
                throw forbidden();
            }
            if (authors.findById(u.authorId()).isEmpty()) {
                throw invalid("authorId");
            }
        }
        if (u.commentStatus() != null && !Set.of("open", "closed").contains(u.commentStatus())) {
            throw invalid("commentStatus");
        }
        if (u.bodyFormat() != null && !Set.of("blocks", "classic").contains(u.bodyFormat())) {
            throw invalid("bodyFormat");
        }
        // null keeps the parent, 0 clears it
        var parent = u.parentId() == null ? r.parentId() : u.parentId() == 0 ? null : u.parentId();
        var parentChanged = "page".equals(r.type()) && !Objects.equals(parent, r.parentId());
        if (parentChanged
                && parent != null
                && (parent == id || items.find(parent).filter(p -> "page".equals(p.type())).isEmpty())) {
            throw invalid("parentId");
        }
        var slug = r.slug();
        var slugChanged = u.slug() != null && !u.slug().equals(r.slug());
        if (slugChanged) {
            var base = Slugs.title(u.slug());
            slug = base.isEmpty() ? null : permalinks.uniqueSlug(r, base);
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
        var saved = items.update(
                id,
                new ItemRepository.Edit(
                        status,
                        or(u.title(), r.title()),
                        slug,
                        or(u.body(), r.body()),
                        or(u.bodyFormat(), r.bodyFormat()),
                        or(u.excerpt(), r.excerpt()),
                        authorChanged ? u.authorId() : r.authorId(),
                        parentChanged ? parent : r.parentId(),
                        publishedAt,
                        publishedLocal,
                        or(u.commentStatus(), r.commentStatus()),
                        or(u.menuOrder(), r.menuOrder()),
                        now
                )
        );
        if ("publish".equals(status)) {
            categoriesChanged |= permalinks.ensureCategory(saved);
        }
        // the permalink is fixed at first publish and moves only with what it is built from
        var pathChanged = slugChanged
                || dateChanged
                || parentChanged
                || categoriesChanged && permalinks.uses("%category%")
                || authorChanged && permalinks.uses("%author%");
        if ("publish".equals(status) && (saved.permalink() == null || pathChanged)) {
            saved = permalinks.assignPermalink(saved);
        }
        if ("pending".equals(status) && !wasPending) {
            reviewNotifications.pendingReview(saved.id(), saved.title(), a.userId());
        }
        revisions.saved("item", id, saved.title(), saved.body(), saved.bodyFormat(), saved.excerpt(), a.userId());
        revisions.deleteAutosave("item", id, a.userId());
        fragments.delete(List.of(id));
        if ("publish".equals(status) || wasPublished) {
            queue.item(id);
            jobs.requestPublish();
        }
        if ("future".equals(status)) {
            jobs.scheduleGoLive(id, saved.publishedAt().toInstant(ZoneOffset.UTC));
        }
        return saved;
    }

    /**
     * An ordinary {@link #update} with the revision's title, body and excerpt and the item's current status.
     */
    @Transactional
    public Item restoreRevision(long id, long revisionId, Actor a) {
        var rev = revisions
            .find(revisionId)
            .filter(v -> "item".equals(v.objectType()) && v.objectId() == id && !v.autosave())
            .orElseThrow(() -> new Refused(404, "not_found", null));
        var r = items
            .find(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        return update(
                id,
                new Update(
                        rev.title(),
                        null,
                        rev.body(),
                        rev.bodyFormat(),
                        rev.excerpt(),
                        r.status(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        r.modifiedAt()
                ),
                a
        );
    }

    @Transactional
    public void autosave(long id, Actor a, String title, String body, String excerpt) {
        // the lock keeps two autosaves of one user from both inserting
        var r = items
            .lock(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        if (!Capabilities.canEdit(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        // the preview renders an autosave in the item's stored format
        html.item(a.role(), title, body, r.bodyFormat(), excerpt);
        guard.close();
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
        var r = items
            .lock(id)
            .orElseThrow(() -> new Refused(404, "not_found", null));
        if (!Capabilities.canTrash(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        if (trash == "trash".equals(r.status())) {
            throw invalid("status");
        }
        if (!trash) {
            // reusable blocks it shows may have changed while it was in the trash
            html.item(a.role(), r.title(), r.body(), r.bodyFormat(), r.excerpt());
        }
        guard.close();
        var wasPublished = "publish".equals(r.status());
        items.setStatus(id, trash ? "trash" : "draft", now());
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

    private static <T> T or(T value, T stored) {
        return value != null ? value : stored;
    }

    private static Refused forbidden() {
        return new Refused(403, "forbidden", null);
    }

    private static Refused invalid(String field) {
        return new Refused(400, "invalid", field);
    }
}
