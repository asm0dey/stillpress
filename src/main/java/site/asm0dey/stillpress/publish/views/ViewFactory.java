package site.asm0dey.stillpress.publish.views;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.blocks.BlockRenderer;
import site.asm0dey.stillpress.blocks.ClassicContent;
import site.asm0dey.stillpress.blocks.RenderContext;
import site.asm0dey.stillpress.db.tables.records.CommentRecord;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.publish.Permalinks;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.CommentRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ApplicationScoped
public class ViewFactory {
    @Inject
    BlockRenderer blocks;
    @Inject
    TermRepository terms;
    @Inject
    CommentRepository comments;
    @Inject
    AuthorRepository authors;
    @Inject
    SettingRepository settings;
    @Inject
    ReusableBlockRepository reusable;

    public SiteView site() {
        return new SiteView(
                settings.get("title").orElse(""),
                settings.get("description").orElse(""),
                settings.get("language").orElse("en"),
                homeUrl()
        );
    }

    public ItemView item(ItemRecord r) {
        var permalink = Objects.requireNonNullElse(r.getPermalink(), "/");
        var encoded = Permalinks.encode(permalink);
        var all = terms.forItem(r.getId());
        var author = r.getAuthorId() == null
                ? Optional.<site.asm0dey.stillpress.db.tables.records.AuthorRecord>empty()
                : authors.findById(r.getAuthorId());
        return new ItemView(
                r.getId(),
                Objects.requireNonNullElse(r.getTitle(), ""),
                encoded,
                homeUrl() + encoded,
                html(r.getBody(), r.getBodyFormat()),
                html(r.getExcerpt(), "classic"),
                instant(r.getPublishedAt()),
                author
                    .map(a -> Objects.requireNonNullElse(a.getDisplayName(), a.getLogin()))
                    .orElse(""),
                author
                    .map(a -> Permalinks.encode("/author/" + a.getSlug() + "/"))
                    .orElse(null),
                links(all, "category"),
                links(all, "post_tag"),
                threaded(comments.approvedFor(r.getId())),
                r.getGuid()
        );
    }

    private String homeUrl() {
        return settings.get("home_url").orElse("").replaceAll("/+$", "");
    }

    private String html(String content, String format) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return "classic".equals(format)
                ? ClassicContent.toHtml(content)
                : blocks.render(content, new RenderContext(reusable::content, 0));
    }

    private List<TermLink> links(List<TermRecord> all, String taxonomy) {
        var base = settings
            .get(taxonomy.equals("category") ? "category_base" : "tag_base")
            .orElse(taxonomy.equals("category") ? "category" : "tag");
        return all
            .stream()
            .filter(t -> taxonomy.equals(t.getTaxonomy()))
            .map(t -> new TermLink(t.getName(), Permalinks.encode("/" + base + "/" + termPath(t) + "/")))
            .toList();
    }

    /**
     * Hierarchical slug path: parent slugs first, as WordPress category links.
     */
    private String termPath(TermRecord t) {
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (
                var cur = t;
                cur != null && seen.add(cur.getId());
                cur = cur.getParentId() == null ? null : terms.findById(cur.getParentId()).orElse(null)) {
            parts.addFirst(cur.getSlug());
            if (!"category".equals(t.getTaxonomy())) {
                break;
            }
        }
        return String.join("/", parts);
    }

    private List<CommentView> threaded(List<CommentRecord> flat) {
        var byParent = new HashMap<Long, List<CommentRecord>>();
        var ids = new HashSet<Long>();
        flat.forEach(c -> ids.add(c.getId()));
        // a reply whose parent is not approved is promoted to top level (key 0)
        flat.forEach(c -> byParent
            .computeIfAbsent(c.getParentId() != null && ids.contains(c.getParentId()) ? c.getParentId() : 0L, k -> new ArrayList<>())
            .add(c));
        return build(byParent, 0L);
    }

    private List<CommentView> build(Map<Long, List<CommentRecord>> byParent, long parent) {
        return byParent
            .getOrDefault(parent, List.of())
            .stream()
            .map(c -> new CommentView(
                    c.getId(),
                    Objects.requireNonNullElse(c.getAuthorName(), ""),
                    c.getAuthorUrl() == null || c.getAuthorUrl().isBlank() ? null : c.getAuthorUrl(),
                    instant(c.getCreatedAt()),
                    ClassicContent.toHtml(c.getContent()),
                    build(byParent, c.getId())
            ))
            .toList();
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
