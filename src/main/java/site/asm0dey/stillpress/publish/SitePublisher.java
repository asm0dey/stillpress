package site.asm0dey.stillpress.publish;

import io.quarkus.qute.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.publish.views.ArchiveView;
import site.asm0dey.stillpress.publish.views.ItemView;
import site.asm0dey.stillpress.publish.views.SiteComment;
import site.asm0dey.stillpress.publish.views.SiteView;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.CommentRepository;
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
    private static final int BATCH = 50;
    private static final Logger LOG = Logger.getLogger(SitePublisher.class);
    @Inject
    ItemRepository items;
    @Inject
    CommentRepository comments;
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
            discardUnlessLive(release);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            discardUnlessLive(release);
            throw e;
        }
    }

    /**
     * Only a release that never went live is removed; the one {@code current} points at is never touched.
     */
    private void discardUnlessLive(Path release) {
        try {
            if (Files.exists(dataDir.current()) && Files.isSameFile(dataDir.current(), release)) {
                return;
            }
            ReleaseSwitcher.deleteTree(release);
        } catch (IOException | RuntimeException e) {
            LOG.warnf(e, "Could not remove unfinished release %s", release);
        }
    }

    private void render(Out out) throws IOException {
        var site = views.site();
        out.perPage = positive("posts_per_page");
        out.perRss = positive("posts_per_rss");
        var summaries = items.findPublishedSummaries();
        // the only thing held for the whole run: ids, dates and links; bodies are loaded a page at a time
        for (var chunk : chunks(summaries)) {
            for (var v : views(chunk)) {
                out.origin = "item:" + v.id();
                var path = Permalinks.decode(v.permalinkEncoded());
                out.page(path, render("plain/single", "site", site, "item", v));
                out.page(
                        path + FEED_SEGMENT + "/",
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
        }
        var posts = summaries
            .stream()
            .filter(r -> "post".equals(r.type()))
            .toList();
        out.origin = "home";
        paged(out, site, "/", site.title(), posts);
        feed(out, site, "/", posts);
        out.origin = "comments feed";
        commentsFeed(out, site);
        termArchives(out, site, posts);
        authorArchives(out, site, posts);
        out.origin = "date archive";
        dateArchives(out, site, posts);
        out.origin = "static";

        out.file("404.html", render("plain/404", "site", site).getBytes(StandardCharsets.UTF_8));
        out.file("robots.txt", "User-agent: *\nDisallow: /admin/\n".getBytes(StandardCharsets.UTF_8));
        for (var a : ASSETS) {
            try (var in = SitePublisher.class.getResourceAsStream("/static-assets/" + a)) {
                out.file("assets/" + a, Objects.requireNonNull(in, a).readAllBytes());
            }
        }
        linkMedia(out);
    }

    /**
     * A positive integer setting, WordPress's 10 when it is missing or invalid.
     */
    private int positive(String key) {
        var raw = settings.get(key).orElse("10");
        try {
            var n = Integer.parseInt(raw.strip());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        LOG.warnf("Invalid %s '%s', using 10", key, raw);
        return 10;
    }

    private static List<List<ItemRepository.Summary>> chunks(List<ItemRepository.Summary> all) {
        var out = new ArrayList<List<ItemRepository.Summary>>();
        for (int i = 0; i < all.size(); i += BATCH) {
            out.add(all.subList(i, Math.min(all.size(), i + BATCH)));
        }
        return out;
    }

    /**
     * Full views of one slice, loaded together and dropped by the caller.
     */
    private List<ItemView> views(List<ItemRepository.Summary> slice) {
        return items
            .findByIds(slice.stream().map(ItemRepository.Summary::id).toList())
            .stream()
            .map(views::item)
            .toList();
    }

    private void termArchives(Out out, SiteView site, List<ItemRepository.Summary> posts) throws IOException {
        var byId = new HashMap<Long, TermRecord>();
        terms.all().forEach(t -> byId.put(t.getId(), t));
        var termIds = terms.termIdsByItem();
        // posts are newest first, so every bucket is too; a post counts for its categories' ancestors as well
        var buckets = new HashMap<Long, List<ItemRepository.Summary>>();
        for (var post : posts) {
            var hit = new LinkedHashSet<Long>();
            for (var id : termIds.getOrDefault(post.id(), List.of())) {
                for (var t = byId.get(id); t != null && hit.add(t.getId()); t = parent(byId, t)) {
                    if (!"category".equals(t.getTaxonomy())) {
                        break;
                    }
                }
            }
            hit.forEach(id -> buckets
                .computeIfAbsent(id, k -> new ArrayList<>())
                .add(post));
        }
        for (var term : byId.values()) {
            // other taxonomies (nav_menu, post_format, ...) have no archive here; WordPress would 404 them
            if (!"category".equals(term.getTaxonomy()) && !"post_tag".equals(term.getTaxonomy())) {
                continue;
            }
            out.origin = "term " + term.getTaxonomy() + ":" + term.getSlug();
            var isCategory = "category".equals(term.getTaxonomy());
            var base = settings.get(isCategory ? "category_base" : "tag_base").orElse(isCategory ? "category" : "tag");
            var path = "/" + base + "/" + termPath(byId, term) + "/";
            var list = buckets.getOrDefault(term.getId(), List.of());
            // WordPress answers an existing term 200 with an empty list, never 404
            paged(out, site, path, term.getName(), list);
            feed(out, site, path, list);
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

    private void authorArchives(Out out, SiteView site, List<ItemRepository.Summary> posts) throws IOException {
        var byAuthor = new HashMap<Long, List<ItemRepository.Summary>>();
        for (var p : posts) {
            if (p.authorId() != null) {
                byAuthor
                    .computeIfAbsent(p.authorId(), k -> new ArrayList<>())
                    .add(p);
            }
        }
        for (var a : authors.all()) {
            out.origin = "author:" + a.getSlug();
            var list = byAuthor.getOrDefault(a.getId(), List.of());
            var path = "/author/" + a.getSlug() + "/";
            paged(out, site, path, Objects.requireNonNullElse(a.getDisplayName(), a.getLogin()), list);
            feed(out, site, path, list);
        }
    }

    private void dateArchives(Out out, SiteView site, List<ItemRepository.Summary> posts) throws IOException {
        var groups = new LinkedHashMap<String, List<ItemRepository.Summary>>();
        for (var p : posts) {
            // WordPress groups by the site-local date, so a post at 01:30 Moscow time is in that day's archive
            if (p.publishedLocal() != null) {
                var d = p.publishedLocal();
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
     * Page 1 at the archive root, pages 2..N at {@code page/N/}; only the page being written is loaded.
     */
    private void paged(Out out, SiteView site, String base, String heading, List<ItemRepository.Summary> all)
            throws IOException {
        var total = Math.max(1, (all.size() + out.perPage - 1) / out.perPage);
        for (int n = 1; n <= total; n++) {
            var slice = all.subList((n - 1) * out.perPage, Math.min(all.size(), n * out.perPage));
            var view = new ArchiveView(
                    heading,
                    views(slice),
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

    /**
     * The newest dated posts; undated ones have no place in a feed.
     */
    private void feed(Out out, SiteView site, String base, List<ItemRepository.Summary> posts) throws IOException {
        var newest = views(posts
            .stream()
            .filter(p -> p.publishedAt() != null)
            .limit(out.perRss)
            .toList());
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

    private void commentsFeed(Out out, SiteView site) throws IOException {
        var newest = comments.newestPublic(out.perRss);
        var ids = newest
            .stream()
            .map(c -> c.getItemId())
            .distinct()
            .toList();
        var byItem = new HashMap<Long, ItemView>();
        items
            .findByIds(ids)
            .forEach(r -> byItem.put(r.getId(), views.item(r)));
        var entries = new ArrayList<SiteComment>();
        for (var c : newest) {
            var item = byItem.get(c.getItemId());
            item
                .allComments()
                .stream()
                .filter(v -> v.id() == ViewFactory.anchorId(c))
                .findFirst()
                .ifPresent(v -> entries.add(new SiteComment(item, v)));
        }
        var last = entries.isEmpty() || entries.getFirst().comment().createdAt() == null
                ? Instant.EPOCH
                : entries.getFirst().comment().createdAt();
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
        /**
         * Posts per archive page, read once per publish.
         */
        int perPage = 10;
        /**
         * Items per feed ({@code posts_per_rss}), read once per publish.
         */
        int perRss = 10;
        String origin = "";
        private final Map<Path, String> written = new HashMap<>();

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
            claim(target);
            Files.createDirectories(target.getParent());
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(source, target);
            }
            files++;
        }

        /**
         * Two pages for one path would silently overwrite each other; say so.
         */
        private void claim(Path target) {
            var earlier = written.put(target, origin);
            if (earlier != null) {
                LOG.warnf("Path %s written twice: %s then %s; the later one wins", target, earlier, origin);
            }
        }

        private void write(Path target, byte[] bytes) throws IOException {
            claim(target);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            files++;
        }
    }
}
