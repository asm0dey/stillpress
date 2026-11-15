package site.asm0dey.stillpress.publish.views;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.blocks.BlockRenderer;
import site.asm0dey.stillpress.blocks.ClassicContent;
import site.asm0dey.stillpress.blocks.RenderContext;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.Item;
import site.asm0dey.stillpress.db.tables.pojos.Term;
import site.asm0dey.stillpress.db.tables.records.CommentRecord;
import site.asm0dey.stillpress.publish.Permalinks;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.CommentRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ApplicationScoped
public class ViewFactory {
    private final BlockRenderer blocks;
    private final TermRepository terms;
    private final CommentRepository comments;
    private final AuthorRepository authors;
    private final SettingRepository settings;
    private final ReusableBlockRepository reusable;

    ViewFactory(
            BlockRenderer blocks,
            TermRepository terms,
            CommentRepository comments,
            AuthorRepository authors,
            SettingRepository settings,
            ReusableBlockRepository reusable
    ) {
        this.blocks = blocks;
        this.terms = terms;
        this.comments = comments;
        this.authors = authors;
        this.settings = settings;
        this.reusable = reusable;
    }

    public SiteView site() {
        return new SiteView(
                settings.get("title").orElse(""),
                settings.get("description").orElse(""),
                settings.get("language").orElse("en"),
                homeUrl()
        );
    }

    public ItemView item(Item r) {
        var permalink = Objects.requireNonNullElse(r.permalink(), "/");
        var encoded = Permalinks.encode(permalink);
        var all = terms.forItem(r.id());
        var author = r.authorId() == null ? Optional.<Author>empty() : authors.findById(r.authorId());
        return new ItemView(
                r.id(),
                Objects.requireNonNullElse(r.title(), ""),
                encoded,
                homeUrl() + encoded,
                html(r.body(), r.bodyFormat()),
                html(r.excerpt(), "classic"),
                instant(r.publishedAt()),
                author
                    .map(a -> Objects.requireNonNullElse(a.displayName(), a.login()))
                    .orElse(""),
                author
                    .map(a -> Permalinks.encode("/author/" + a.slug() + "/"))
                    .orElse(null),
                links(all, "category"),
                links(all, "post_tag"),
                threaded(comments.approvedFor(r.id())),
                r.guid()
        );
    }

    // the lookbehind keeps the regex linear
    @SuppressWarnings("java:S8786")
    private String homeUrl() {
        return settings.get("home_url").orElse("").replaceAll("(?<!/)/+$", "");
    }

    private String html(String content, String format) {
        return html(content, format, reusable::content);
    }

    /**
     * An item's body or excerpt as published, with reusable blocks looked up through {@code reusableBlockContent}.
     */
    public String html(String content, String format, Function<Long, Optional<String>> reusableBlockContent) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return "classic".equals(format)
                ? ClassicContent.toHtml(content)
                : blocks.render(content, new RenderContext(reusableBlockContent, 0));
    }

    private List<TermLink> links(List<Term> all, String taxonomy) {
        var base = settings
            .get(taxonomy.equals("category") ? "category_base" : "tag_base")
            .orElse(taxonomy.equals("category") ? "category" : "tag");
        return all
            .stream()
            .filter(t -> taxonomy.equals(t.taxonomy()))
            .map(t -> new TermLink(t.name(), Permalinks.encode("/" + base + "/" + termPath(t) + "/")))
            .toList();
    }

    /**
     * Hierarchical slug path: parent slugs first, as WordPress category links.
     */
    private String termPath(Term t) {
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (
                var cur = t;
                cur != null && seen.add(cur.id());
                cur = cur.parentId() == null ? null : terms.findById(cur.parentId()).orElse(null)) {
            parts.addFirst(cur.slug());
            if (!"category".equals(t.taxonomy())) {
                break;
            }
        }
        return String.join("/", parts);
    }

    static List<CommentView> threaded(List<CommentRecord> flat) {
        var byId = new HashMap<Long, CommentRecord>();
        flat.forEach(c -> byId.put(c.getId(), c));
        var byParent = new HashMap<Long, List<CommentRecord>>();
        // orphans and members of parent cycles are promoted to top level (key 0)
        flat.forEach(c -> byParent
            .computeIfAbsent(c.getParentId() != null && byId.containsKey(c.getParentId()) && !inCycle(c, byId)
                    ? c.getParentId()
                    : 0L, k -> new ArrayList<>())
            .add(c));
        return build(byParent, 0L);
    }

    private static boolean inCycle(CommentRecord c, Map<Long, CommentRecord> byId) {
        var seen = new HashSet<Long>();
        for (var p = c.getParentId(); p != null && seen.add(p); p = byId.containsKey(p)
                ? byId.get(p).getParentId()
                : null) {
            if (p.equals(c.getId())) {
                return true;
            }
        }
        return false;
    }

    private static List<CommentView> build(Map<Long, List<CommentRecord>> byParent, long parent) {
        return byParent
            .getOrDefault(parent, List.of())
            .stream()
            .map(c -> new CommentView(
                    anchorId(c),
                    Objects.requireNonNullElse(c.getAuthorName(), ""),
                    c.getAuthorUrl() == null || c.getAuthorUrl().isBlank() ? null : c.getAuthorUrl(),
                    instant(c.getCreatedAt()),
                    CommentSanitizer.clean(ClassicContent.toHtml(c.getContent())),
                    build(byParent, c.getId())
            ))
            .toList();
    }

    /**
     * WordPress anchors and comment-feed GUIDs use {@code comment_ID}: the WXR id, or ours for comments made here.
     */
    // shortcut: a native comment's row id could equal an imported comment's WXR id; give native comments their own id space when Stillpress takes comments
    public static long anchorId(CommentRecord c) {
        return c.getSourceId() != null ? c.getSourceId() : c.getId();
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
