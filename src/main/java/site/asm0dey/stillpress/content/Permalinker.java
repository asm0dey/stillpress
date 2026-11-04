package site.asm0dey.stillpress.content;

import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Slugs, permalinks and the redirects a moved permalink leaves, shared by the editor and go-live.
 */
@ApplicationScoped
public class Permalinker {
    private static final int MAX_ATTEMPTS = 1000;
    @Inject
    DSLContext db;
    @Inject
    SettingRepository settings;
    @Inject
    RedirectRepository redirects;
    @Inject
    TermRepository terms;
    @Inject
    TermService termService;

    /**
     * Whether posts' permalinks contain {@code token}, such as {@code %category%}.
     */
    public boolean uses(String token) {
        return settings.get("permalink_structure").orElse(PermalinkStructure.DEFAULT).contains(token);
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
        ensureCategory(r);
        var slug = base;
        var permalink = permalink(r, slug);
        for (int n = 2; slugTaken(r, slug) || taken(permalink, r.getId()); n++) {
            if (n > MAX_ATTEMPTS) {
                // a structure with neither %postname% nor %post_id% never gets unique
                throw new IllegalStateException("No free permalink for '"
                        + base
                        + "' after "
                        + MAX_ATTEMPTS
                        + " tries");
            }
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

    /**
     * A post that goes public without a category gets the site's default one, as WordPress does; true when it did.
     * The default is setting {@code default_category}, else "Uncategorized" ("Без рубрики" on a Russian site),
     * created on first use and remembered in the setting. Never at setup: the import guard needs an empty
     * {@code term} table.
     */
    public boolean ensureCategory(ItemRecord r) {
        if (!"post".equals(r.getType()) || r.getId() == null || !terms.idsForItem(r.getId(), "category").isEmpty()) {
            return false;
        }
        terms.setForItem(r.getId(), "category", List.of(defaultCategory()));
        return true;
    }

    private long defaultCategory() {
        var chosen = settings
            .get("default_category")
            .map(Long::valueOf)
            .flatMap(terms::findById)
            .filter(t -> "category".equals(t.getTaxonomy()));
        if (chosen.isPresent()) {
            return chosen.get().getId();
        }
        var name = settings.get("language").orElse("en").startsWith("ru") ? "Без рубрики" : "Uncategorized";
        var term = terms
            .findBySlug("category", Slugs.title(name))
            .orElseGet(() -> termService.createTerm(
                    "category",
                    name,
                    null,
                    new ItemService.Actor(0, Role.ADMINISTRATOR)
            ));
        settings.put("default_category", String.valueOf(term.getId()));
        return term.getId();
    }

    public String uniqueSlug(ItemRecord r, String base) {
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

    public ZoneId zone() {
        return ZoneId.of(settings.get("timezone").orElse("UTC"));
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
    public String termPath(long termId) {
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
