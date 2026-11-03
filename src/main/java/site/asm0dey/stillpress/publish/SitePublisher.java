package site.asm0dey.stillpress.publish;

import io.quarkus.qute.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.publish.views.ArchiveView;
import site.asm0dey.stillpress.publish.views.ItemView;
import site.asm0dey.stillpress.publish.views.SiteComment;
import site.asm0dey.stillpress.publish.views.SiteView;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Renders the whole site into a fresh release and switches {@code current} to it only once every file is written.
 */
@ApplicationScoped
public class SitePublisher {
    /**
     * Feeds are written as {@code <path>/feed/index.html}; the static route serves any path ending in {@code /feed/}
     * as {@code application/rss+xml}.
     */
    public static final String FEED_SEGMENT = "feed";
    static final List<String> ASSETS = List.of("block-library.css", "plain.css", "plain.js");
    private static final int FEED_SIZE = 10;
    @Inject
    ItemRepository items;
    @Inject
    TermRepository terms;
    @Inject
    AuthorRepository authors;
    @Inject
    SettingRepository settings;
    @Inject
    ViewFactory views;
    @Inject
    Engine engine;
    @Inject
    DataDir dataDir;
    @Inject
    ReleaseSwitcher switcher;

    public PublishResult publishAll() {
        var start = Instant.now();
        var release = switcher.newRelease();
        try {
            var out = new Out(release);
            render(out);
            switcher.activate(release);
            return new PublishResult(release, out.files, Duration.between(start, Instant.now()));
        } catch (IOException e) {
            discard(release);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            discard(release);
            throw e;
        }
    }

    private static void discard(Path release) {
        try {
            ReleaseSwitcher.deleteTree(release);
        } catch (IOException | RuntimeException ignored) {
            // best effort: the release was never activated, so a leftover is harmless and is pruned later
        }
    }

    private void render(Out out) throws IOException {
        var site = views.site();
        var records = items.findPublished();
        var viewById = new LinkedHashMap<Long, ItemView>();
        records.forEach(r -> viewById.put(r.getId(), views.item(r)));
        var posts = records
            .stream()
            .filter(r -> "post".equals(r.getType()))
            .toList();
        var postViews = posts.stream().map(r -> viewById.get(r.getId())).toList();

        for (var v : viewById.values()) {
            out.page(decoded(v), render("plain/single", "site", site, "item", v));
            out.page(
                    decoded(v) + FEED_SEGMENT + "/",
                    render(
                            "feed/comments-rss2.xml",
                            "site",
                            site,
                            "item",
                            v,
                            "selfUrl",
                            site.homeUrl() + v.permalinkEncoded() + FEED_SEGMENT + "/"
                    )
            );
        }
        paged(out, site, "/", site.title(), postViews);
        feed(out, site, "/", postViews);
        commentsFeed(out, site, viewById.values());
        termArchives(out, site, posts, viewById);
        authorArchives(out, site, posts, viewById);
        dateArchives(out, site, postViews);

        out.file("404.html", render("plain/404", "site", site).getBytes(StandardCharsets.UTF_8));
        out.file("robots.txt", "User-agent: *\nDisallow: /admin/\n".getBytes(StandardCharsets.UTF_8));
        for (var a : ASSETS) {
            try (var in = SitePublisher.class.getResourceAsStream("/static-assets/" + a)) {
                out.file("assets/" + a, Objects.requireNonNull(in, a).readAllBytes());
            }
        }
        linkMedia(out);
    }

    private String decoded(ItemView v) {
        return Permalinks.decode(v.permalinkEncoded());
    }

    private void termArchives(Out out, SiteView site, List<ItemRecord> posts, Map<Long, ItemView> viewById)
            throws IOException {
        var byId = new HashMap<Long, TermRecord>();
        terms.all().forEach(t -> byId.put(t.getId(), t));
        var termIds = terms.termIdsByItem();
        // newest first, since posts are; a post counts for its categories' ancestors too, like WordPress
        var buckets = new LinkedHashMap<Long, List<ItemView>>();
        for (var post : posts) {
            var hit = new LinkedHashSet<Long>();
            for (var id : termIds.getOrDefault(post.getId(), List.of())) {
                for (var t = byId.get(id); t != null && hit.add(t.getId()); t = parent(byId, t)) {
                    if (!"category".equals(t.getTaxonomy())) {
                        break;
                    }
                }
            }
            hit.forEach(id -> buckets
                .computeIfAbsent(id, k -> new ArrayList<>())
                .add(viewById.get(post.getId())));
        }
        for (var e : buckets.entrySet()) {
            var term = byId.get(e.getKey());
            var isCategory = "category".equals(term.getTaxonomy());
            var base = settings.get(isCategory ? "category_base" : "tag_base").orElse(isCategory ? "category" : "tag");
            var path = "/" + base + "/" + termPath(byId, term) + "/";
            paged(out, site, path, term.getName(), e.getValue());
            feed(out, site, path, e.getValue());
        }
    }

    private static TermRecord parent(Map<Long, TermRecord> byId, TermRecord t) {
        return t.getParentId() == null ? null : byId.get(t.getParentId());
    }

    private static String termPath(Map<Long, TermRecord> byId, TermRecord t) {
        if (!"category".equals(t.getTaxonomy())) {
            return t.getSlug();
        }
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (var cur = t; cur != null && seen.add(cur.getId()); cur = parent(byId, cur)) {
            parts.addFirst(cur.getSlug());
        }
        return String.join("/", parts);
    }

    private void authorArchives(Out out, SiteView site, List<ItemRecord> posts, Map<Long, ItemView> viewById)
            throws IOException {
        for (var a : authors.all()) {
            var list = posts
                .stream()
                .filter(p -> Objects.equals(p.getAuthorId(), a.getId()))
                .map(p -> viewById.get(p.getId()))
                .toList();
            if (!list.isEmpty()) {
                var path = "/author/" + a.getSlug() + "/";
                paged(out, site, path, Objects.requireNonNullElse(a.getDisplayName(), a.getLogin()), list);
                feed(out, site, path, list);
            }
        }
    }

    private void dateArchives(Out out, SiteView site, List<ItemView> posts) throws IOException {
        var groups = new LinkedHashMap<String, List<ItemView>>();
        for (var p : posts) {
            if (p.publishedAt() != null) {
                var d = p.publishedAt().atOffset(ZoneOffset.UTC);
                for (var key :
                        List.of(
                                String.format("/%04d/", d.getYear()),
                                String.format("/%04d/%02d/", d.getYear(), d.getMonthValue()),
                                String.format("/%04d/%02d/%02d/", d.getYear(), d.getMonthValue(), d.getDayOfMonth())
                )) {
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
            }
        }
        for (var e : groups.entrySet()) {
            paged(out, site, e.getKey(), e.getKey().substring(1, e.getKey().length() - 1), e.getValue());
        }
    }

    /**
     * Page 1 at the archive root, pages 2..N at {@code page/N/}.
     */
    private void paged(Out out, SiteView site, String base, String heading, List<ItemView> all) throws IOException {
        var perPage = settings
            .get("posts_per_page")
            .map(Integer::parseInt)
            .filter(n -> n > 0)
            .orElse(10);
        var total = Math.max(1, (all.size() + perPage - 1) / perPage);
        for (int n = 1; n <= total; n++) {
            var slice = all.subList((n - 1) * perPage, Math.min(all.size(), n * perPage));
            var view = new ArchiveView(
                    heading,
                    slice,
                    n,
                    total,
                    n > 1 ? Permalinks.encode(pagePath(base, n - 1)) : null,
                    n < total ? Permalinks.encode(pagePath(base, n + 1)) : null
            );
            out.page(pagePath(base, n), render("plain/archive", "site", site, "archive", view));
        }
    }

    private static String pagePath(String base, int n) {
        return n == 1 ? base : base + "page/" + n + "/";
    }

    private void feed(Out out, SiteView site, String base, List<ItemView> posts) throws IOException {
        var newest = posts.stream().limit(FEED_SIZE).toList();
        var path = base + FEED_SEGMENT + "/";
        var last = newest.isEmpty() ? Instant.EPOCH : newest.getFirst().publishedAt();
        out.page(
                path,
                render(
                        "feed/rss2.xml",
                        "site",
                        site,
                        "items",
                        newest,
                        "selfUrl",
                        site.homeUrl() + Permalinks.encode(path),
                        "lastBuildDate",
                        ItemView.rfc1123(last)
                )
        );
    }

    private void commentsFeed(Out out, SiteView site, Collection<ItemView> all) throws IOException {
        var entries = all
            .stream()
            .flatMap(i -> i.allComments().stream().map(c -> new SiteComment(i, c)))
            .sorted(Comparator
                .comparing((SiteComment e) -> e.comment().createdAt())
                .reversed())
            .limit(FEED_SIZE)
            .toList();
        var last = entries.isEmpty() ? Instant.EPOCH : entries.getFirst().comment().createdAt();
        out.page(
                "/comments/" + FEED_SEGMENT + "/",
                render(
                        "feed/comments-site-rss2.xml",
                        "site",
                        site,
                        "entries",
                        entries,
                        "selfUrl",
                        site.homeUrl() + "/comments/" + FEED_SEGMENT + "/",
                        "lastBuildDate",
                        ItemView.rfc1123(last)
                )
        );
    }

    /**
     * Hard links keep a release cheap; a copy is the fallback across filesystems.
     */
    private void linkMedia(Out out) throws IOException {
        var media = dataDir.media();
        try (var walk = Files.walk(media)) {
            for (var src : walk.filter(Files::isRegularFile).toList()) {
                out.link("media/" + media.relativize(src).toString().replace('\\', '/'), src);
            }
        }
    }

    private String render(String template, Object... kv) {
        var t = engine.getTemplate(template).instance();
        for (int i = 0; i < kv.length; i += 2) {
            t.data((String) kv[i], kv[i + 1]);
        }
        return t.render();
    }

    private static final class Out {
        final Path root;
        int files;

        Out(Path root) {
            this.root = root;
        }

        /**
         * {@code decodedPath} is a directory path; written as its {@code index.html}.
         */
        void page(String decodedPath, String html) throws IOException {
            write(Permalinks.toFile(root, decodedPath), html.getBytes(StandardCharsets.UTF_8));
        }

        void file(String relative, byte[] bytes) throws IOException {
            write(root.resolve(relative), bytes);
        }

        void link(String relative, Path source) throws IOException {
            var target = root.resolve(relative);
            Files.createDirectories(target.getParent());
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(source, target);
            }
            files++;
        }

        private void write(Path target, byte[] bytes) throws IOException {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            files++;
        }
    }
}
