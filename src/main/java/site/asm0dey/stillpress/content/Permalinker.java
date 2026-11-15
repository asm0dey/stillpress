package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.Item;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Slugs, permalinks and the redirects a moved permalink leaves, shared by the editor and go-live.
 */
@ApplicationScoped
public class Permalinker {
    private static final int MAX_ATTEMPTS = 1000;
    private final ItemRepository items;
    private final AuthorRepository authors;
    private final SettingRepository settings;
    private final RedirectRepository redirects;
    private final TermRepository terms;
    private final TermService termService;

    Permalinker(
            ItemRepository items,
            AuthorRepository authors,
            SettingRepository settings,
            RedirectRepository redirects,
            TermRepository terms,
            TermService termService
    ) {
        this.items = items;
        this.authors = authors;
        this.settings = settings;
        this.redirects = redirects;
        this.terms = terms;
        this.termService = termService;
    }

    /**
     * Whether posts' permalinks contain {@code token}, such as {@code %category%}.
     */
    public boolean uses(String token) {
        return settings.get("permalink_structure").orElse(PermalinkStructure.DEFAULT).contains(token);
    }

    /**
     * The item's date in the site's time zone, null when it has none.
     */
    public LocalDateTime localDate(Item r) {
        return localDate(r.publishedLocal(), r.publishedAt());
    }

    public LocalDateTime localDate(LocalDateTime publishedLocal, LocalDateTime publishedAt) {
        if (publishedLocal != null) {
            return publishedLocal;
        }
        return publishedAt == null
                ? null
                : publishedAt.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone()).toLocalDateTime();
    }

    /**
     * Gives {@code r} its slug and permalink as WordPress would: posts from the site's {@code permalink_structure},
     * pages from their ancestors' slugs. The slug is made unique among items of the same type (and the permalink
     * among all) with {@code -2}, {@code -3}... Stores both and returns the stored item; the redirects are written
     * here too: on the first permalink the shortlinks {@code ?p=} (and {@code ?page_id=} for pages), on a change old
     * path to new.
     */
    public Item assignPermalink(Item r) {
        var old = r.permalink();
        var base =
                r.slug() == null || r.slug().isBlank()
                ? Slugs.title(Objects.requireNonNullElse(r.title(), ""))
                : r.slug();
        if (base.isEmpty()) {
            base = String.valueOf(r.sourceId());
        }
        ensureCategory(r);
        var slug = base;
        var permalink = permalink(r, slug);
        for (int n = 2; items.slugTaken(r.type(), slug, r.id()) || items.permalinkTaken(permalink, r.id()); n++) {
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
        var stored = items.setPath(r.id(), slug, permalink);
        if (old == null && r.sourceId() != null) {
            redirects.put("?p=" + r.sourceId(), permalink);
            if ("page".equals(r.type())) {
                redirects.put("?page_id=" + r.sourceId(), permalink);
            }
        } else if (old != null && !old.equals(permalink)) {
            redirects.put(old, permalink);
        }
        return stored;
    }

    /**
     * A post that goes public without a category gets the site's default one, as WordPress does; true when it did.
     * The default is setting {@code default_category}, else "Uncategorized" ("Без рубрики" on a Russian site),
     * created on first use and remembered in the setting. Never at setup: the import guard needs an empty
     * {@code term} table.
     */
    public boolean ensureCategory(Item r) {
        if (!"post".equals(r.type()) || r.id() == null || !terms.idsForItem(r.id(), "category").isEmpty()) {
            return false;
        }
        terms.setForItem(r.id(), "category", List.of(defaultCategory()));
        return true;
    }

    /**
     * {@link #ensureCategory(Item)} for many items at once: the posts among {@code itemIds} left without a
     * category get the default one. Returns its id when any did.
     */
    public Optional<Long> ensureCategory(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Optional.empty();
        }
        var bare = terms.uncategorizedPosts(itemIds);
        if (bare.isEmpty()) {
            return Optional.empty();
        }
        var category = defaultCategory();
        terms.addToItems(bare, category);
        return Optional.of(category);
    }

    private long defaultCategory() {
        var chosen = settings
            .get("default_category")
            .map(Long::valueOf)
            .flatMap(terms::findById)
            .filter(t -> "category".equals(t.taxonomy()));
        if (chosen.isPresent()) {
            return chosen.get().id();
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
        settings.put("default_category", String.valueOf(term.id()));
        return term.id();
    }

    public String uniqueSlug(Item r, String base) {
        var slug = base;
        for (int n = 2; items.slugTaken(r.type(), slug, r.id()); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private String permalink(Item r, String slug) {
        if ("page".equals(r.type())) {
            var parts = new ArrayDeque<String>();
            parts.addFirst(slug);
            var seen = new HashSet<Long>();
            for (var p = r.parentId(); p != null && seen.add(p); ) {
                var parent = items.find(p).orElse(null);
                if (parent == null) {
                    break;
                }
                parts.addFirst(parent.slug());
                p = parent.parentId();
            }
            return "/" + String.join("/", parts) + "/";
        }
        var structure = settings.get("permalink_structure").orElse(PermalinkStructure.DEFAULT);
        var local = Objects.requireNonNullElseGet(localDate(r), () -> LocalDateTime.now(zone()));
        var author = r.authorId() == null ? null : authors.findById(r.authorId()).map(Author::slug).orElse(null);
        return PermalinkStructure.render(
                structure,
                new PermalinkStructure.Fields(
                        Objects.requireNonNullElse(r.sourceId(), 0L),
                        slug,
                        local,
                        categoryPath(r.id()),
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
        var first =
                itemId == null ? null : terms
            .idsForItem(itemId, "category")
            .stream()
            .min(Long::compare)
            .orElse(null);
        return first == null ? "uncategorized" : termPath(first);
    }

    /**
     * The term's slug under its ancestors' slugs.
     */
    public String termPath(long termId) {
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (Long t = termId; t != null && seen.add(t); ) {
            var term = terms.findById(t).orElse(null);
            if (term == null) {
                break;
            }
            parts.addFirst(term.slug());
            t = term.parentId();
        }
        return String.join("/", parts);
    }
}
