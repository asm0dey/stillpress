package site.asm0dey.stillpress.publish;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.Term;
import site.asm0dey.stillpress.publish.views.ArchiveView;
import site.asm0dey.stillpress.publish.views.EntryView;
import site.asm0dey.stillpress.publish.views.ItemView;
import site.asm0dey.stillpress.publish.views.SiteComment;
import site.asm0dey.stillpress.publish.views.SiteView;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.CommentRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.PublishedItemRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Renders the site into a fresh release and switches {@code current} to it only once every file is written. A batch
 * re-renders only what it touches and hard-links every other file from the live release.
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
    private static final Pattern PAGE = Pattern.compile("(.*)page/[0-9]+/index\\.html");
    private final ItemRepository items;
    private final CommentRepository comments;
    private final TermRepository terms;
    private final AuthorRepository authors;
    private final SettingRepository settings;
    private final ViewFactory views;
    private final Engine engine;
    private final DataDir dataDir;
    private final ReleaseSwitcher switcher;
    private final FragmentRepository fragments;
    private final PublishedItemRepository published;
    private final PublishQueue queue;
    private final PublishStatus status;

    SitePublisher(
            ItemRepository items,
            CommentRepository comments,
            TermRepository terms,
            AuthorRepository authors,
            SettingRepository settings,
            ViewFactory views,
            Engine engine,
            DataDir dataDir,
            ReleaseSwitcher switcher,
            FragmentRepository fragments,
            PublishedItemRepository published,
            PublishQueue queue,
            PublishStatus status
    ) {
        this.items = items;
        this.comments = comments;
        this.terms = terms;
        this.authors = authors;
        this.settings = settings;
        this.views = views;
        this.engine = engine;
        this.dataDir = dataDir;
        this.switcher = switcher;
        this.fragments = fragments;
        this.published = published;
        this.queue = queue;
        this.status = status;
    }

    public PublishResult publishAll() {
        return publish(new PublishQueue.Batch(Set.of(), Set.of(), Set.of(), false, true));
    }

    /**
     * Full when the batch asks for it or there is nothing to build on (no live release, no {@code published_item}
     * rows); otherwise only the batch's items, the listings they were and are in, and the comments feed.
     */
    public PublishResult publish(PublishQueue.Batch batch) {
        try {
            var r = run(batch);
            status.finished(r.files());
            LOG.infof("Published %d files in %d ms into %s", r.files(), r.took().toMillis(), r.release());
            var t = System.nanoTime();
            switcher.prune();
            LOG.infof("Pruned old releases in %d ms", (System.nanoTime() - t) / 1_000_000);
            return r;
        } catch (RuntimeException e) {
            status.failed(Objects.requireNonNullElse(e.getMessage(), e.toString()));
            throw e;
        }
    }

    private PublishResult run(PublishQueue.Batch batch) {
        var start = Instant.now();
        var live = liveRelease();
        var incremental = !batch.all() && live != null && published.any();
        LOG.infof("Publish started (%s)", incremental ? "incremental" : "full");
        var release = switcher.newRelease();
        Out out;
        try {
            out = new Out(release, incremental ? live : null, batch, status);
            var t = System.nanoTime();
            if (incremental) {
                // a fragment stored by a run that read the item just before it was saved is dropped here
                fragments.delete(batch.items());
            } else {
                fragments.clear();
            }
            out.phases.add("fragments", t);
            render(out);
            t = System.nanoTime();
            switcher.activate(release);
            out.phases.add("activate", t);
        } catch (IOException e) {
            discardUnlessLive(release);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            discardUnlessLive(release);
            throw e;
        }
        var t = System.nanoTime();
        record(out);
        out.phases.add("record", t);
        LOG.infof("Publish phases (%s): %s", incremental ? "incremental" : "full", out.phases);
        return new PublishResult(release, out.files, Duration.between(start, Instant.now()));
    }

    private Path liveRelease() {
        try {
            return Files.exists(dataDir.current()) ? dataDir.current().toRealPath() : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The release is live already; stale bookkeeping would make later runs skip paths, so a failure forces a full one.
     */
    private void record(Out out) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (out.live == null) {
                    published.replaceAll(out.rows);
                    return;
                }
                var gone = new HashSet<>(out.batch.items());
                out.rows.forEach(r -> gone.remove(r.itemId()));
                published.delete(gone);
                published.upsert(out.rows);
            });
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not record the published items of %s; queueing a full publish", out.root);
            queue.all();
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
        var t = System.nanoTime();
        var site = views.site();
        out.perPage = positive("posts_per_page");
        out.perRss = positive("posts_per_rss");
        var summaries = items.findPublishedSummaries();
        var posts = summaries
            .stream()
            .filter(r -> "post".equals(r.type()))
            .toList();
        var listings = new ArrayList<Listing>();
        listings.add(new Listing("home", "/", site.title(), posts, true));
        termListings(listings, posts);
        authorListings(listings, posts);
        dateListings(listings, posts);
        var bases = new HashMap<Long, List<String>>();
        for (var l : listings) {
            l
                .posts()
                .forEach(p -> bases
                    .computeIfAbsent(p.id(), k -> new ArrayList<>())
                    .add(l.base()));
        }
        var rendered = summaries
            .stream()
            .filter(s -> out.wantsItem(s.id()))
            .toList();
        out.rows = rendered
            .stream()
            .map(s -> new PublishedItemRepository.Row(s.id(), s.permalink(), bases.getOrDefault(s.id(), List.of())))
            .toList();
        out.phases.add("snapshot", t);
        if (out.live != null) {
            linkUntouched(out);
        }
        // shortcut: media files are not counted, so done can pass total; count them if the bar ever matters
        var total = rendered.size() * 2
                + (out.commentsFeedCarried ? 0 : 1)
                + (out.live == null ? 2 + ASSETS.size() : 0);
        for (var l : listings) {
            if (out.wantsBase(l.base())) {
                total += Math.max(1, (l.posts().size() + out.perPage - 1) / out.perPage) + (l.feed() ? 1 : 0);
            }
        }
        status.running(total);
        // the only thing held for the whole run: ids, dates and links; bodies are loaded a page at a time
        t = System.nanoTime();
        for (var chunk : chunks(rendered)) {
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
        out.phases.add("items", t);
        for (var l : listings) {
            if (out.wantsBase(l.base())) {
                out.origin = l.origin();
                t = System.nanoTime();
                paged(out, site, l.base(), l.heading(), l.posts());
                out.phases.add("listing pages", t);
                if (l.feed()) {
                    t = System.nanoTime();
                    feed(out, site, l.base(), l.posts());
                    out.phases.add("feeds", t);
                }
            }
        }
        out.origin = "comments feed";
        t = System.nanoTime();
        if (!out.commentsFeedCarried) {
            commentsFeed(out, site);
        }
        out.phases.add("comments feed", t);
        t = System.nanoTime();
        out.origin = "static";
        if (out.live == null) {
            out.file("404.html", render("plain/404", "site", site).getBytes(StandardCharsets.UTF_8));
            out.file("robots.txt", "User-agent: *\nDisallow: /admin/\n".getBytes(StandardCharsets.UTF_8));
            for (var a : ASSETS) {
                try (var in = SitePublisher.class.getResourceAsStream("/static-assets/" + a)) {
                    out.file("assets/" + a, Objects.requireNonNull(in, a).readAllBytes());
                }
            }
        }
        if (out.live == null || out.batch.media()) {
            linkMedia(out);
        }
        out.phases.add("static+media", t);
    }

    /**
     * One archive: its base path, its posts newest first, and whether it has a feed (date archives do not).
     */
    private record Listing(String origin, String base, String heading, List<ItemRepository.Summary> posts, boolean feed) {}

    /**
     * Links every live file the batch cannot have changed: anything but the listing pages and feeds of the old and new
     * bases, the old and new item pages and their feeds, and the comments feed. A dirty path covers only its own
     * {@code index.html}, never the item pages below it.
     */
    private void linkUntouched(Out out) throws IOException {
        var bases = new HashSet<>(out.batch.bases());
        var paths = new HashSet<String>();
        for (var old : published.find(out.batch.items()).values()) {
            bases.addAll(old.bases());
            paths.add(old.permalink());
        }
        for (var now : out.rows) {
            bases.addAll(now.bases());
            paths.add(now.permalink());
        }
        out.dirtyBases = bases;
        var skip = new HashSet<String>();
        out.commentsFeedCarried = !comments.anyComment(out.batch.items())
                && Files.exists(out.live.resolve("comments/" + FEED_SEGMENT + "/index.html"));
        if (!out.commentsFeedCarried) {
            skip.add("comments/" + FEED_SEGMENT + "/index.html");
        }
        var baseDirs = new HashSet<String>();
        for (var p : Stream.concat(bases.stream(), paths.stream()).toList()) {
            var dir = p.replaceFirst("^/+", "");
            skip.add(dir + "index.html");
            skip.add(dir + FEED_SEGMENT + "/index.html");
        }
        bases.forEach(b -> baseDirs.add(b.replaceFirst("^/+", "")));
        var t = System.nanoTime();
        var carried = new ArrayList<Map.Entry<String, Path>>();
        try (var walk = Files.walk(out.live)) {
            for (var src : walk.filter(Files::isRegularFile).toList()) {
                var rel = out.live.relativize(src).toString().replace('\\', '/');
                var page = PAGE.matcher(rel);
                if (!skip.contains(rel) && !(page.matches() && baseDirs.contains(page.group(1)))) {
                    carried.add(Map.entry(rel, src));
                }
            }
        }
        out.phases.add("walk", t);
        t = System.nanoTime();
        for (var e : carried) {
            out.hardLink(e.getKey(), e.getValue());
        }
        out.phases.add("link", t);
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

    private void termListings(List<Listing> listings, List<ItemRepository.Summary> posts) {
        var byId = new HashMap<Long, Term>();
        terms.all().forEach(t -> byId.put(t.id(), t));
        var termIds = terms.termIdsByItem();
        // posts are newest first, so every bucket is too; a post counts for its categories' ancestors as well
        var buckets = new HashMap<Long, List<ItemRepository.Summary>>();
        for (var post : posts) {
            var hit = new LinkedHashSet<Long>();
            for (var id : termIds.getOrDefault(post.id(), List.of())) {
                for (var t = byId.get(id); t != null && hit.add(t.id()); t = parent(byId, t)) {
                    if (!"category".equals(t.taxonomy())) {
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
            if (!"category".equals(term.taxonomy()) && !"post_tag".equals(term.taxonomy())) {
                continue;
            }
            var isCategory = "category".equals(term.taxonomy());
            var base = settings.get(isCategory ? "category_base" : "tag_base").orElse(isCategory ? "category" : "tag");
            // WordPress answers an existing term 200 with an empty list, never 404
            listings.add(
                    new Listing(
                            "term " + term.taxonomy() + ":" + term.slug(),
                            "/" + base + "/" + termPath(byId, term) + "/",
                            term.name(),
                            buckets.getOrDefault(term.id(), List.of()),
                            true
                    )
            );
        }
    }

    private static Term parent(Map<Long, Term> byId, Term t) {
        return t.parentId() == null ? null : byId.get(t.parentId());
    }

    private static String termPath(Map<Long, Term> byId, Term t) {
        if (!"category".equals(t.taxonomy())) {
            return t.slug();
        }
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (var cur = t; cur != null && seen.add(cur.id()); cur = parent(byId, cur)) {
            parts.addFirst(cur.slug());
        }
        return String.join("/", parts);
    }

    private void authorListings(List<Listing> listings, List<ItemRepository.Summary> posts) {
        var byAuthor = new HashMap<Long, List<ItemRepository.Summary>>();
        for (var p : posts) {
            if (p.authorId() != null) {
                byAuthor
                    .computeIfAbsent(p.authorId(), k -> new ArrayList<>())
                    .add(p);
            }
        }
        for (var a : authors.all()) {
            listings.add(
                    new Listing(
                            "author:" + a.slug(),
                            "/author/" + a.slug() + "/",
                            Objects.requireNonNullElse(a.displayName(), a.login()),
                            byAuthor.getOrDefault(a.id(), List.of()),
                            true
                    )
            );
        }
    }

    private void dateListings(List<Listing> listings, List<ItemRepository.Summary> posts) {
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
        groups.forEach((base, list) -> listings.add(
                new Listing("date archive", base, base.substring(1, base.length() - 1), list, false)
        ));
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
                    entries(slice),
                    n,
                    total,
                    n > 1 ? Permalinks.encode(pagePath(base, n - 1)) : null,
                    n < total ? Permalinks.encode(pagePath(base, n + 1)) : null
            );
            out.page(pagePath(base, n), render("plain/archive", "site", site, "archive", view));
        }
    }

    /**
     * Archive entries of one page from {@code item_fragment}.
     */
    private List<String> entries(List<ItemRepository.Summary> slice) {
        return fragments(FragmentRepository.ENTRY, slice, v -> render("plain/entry", "entry", EntryView.of(v)));
    }

    /**
     * One kind of cached fragment for each item of the slice, in slice order; the missing ones are rendered and stored.
     */
    private List<String> fragments(String kind, List<ItemRepository.Summary> slice, Function<ItemView, String> renderer) {
        var html = new HashMap<>(fragments.find(kind, slice.stream().map(ItemRepository.Summary::id).toList()));
        var fresh = new HashMap<Long, String>();
        for (var v : views(slice
            .stream()
            .filter(s -> !html.containsKey(s.id()))
            .toList())) {
            fresh.put(v.id(), renderer.apply(v));
        }
        fragments.put(kind, fresh);
        html.putAll(fresh);
        return slice.stream().map(s -> html.get(s.id())).toList();
    }

    private static String pagePath(String base, int n) {
        return n == 1 ? base : base + "page/" + n + "/";
    }

    /**
     * The newest dated posts; undated ones have no place in a feed.
     */
    private void feed(Out out, SiteView site, String base, List<ItemRepository.Summary> posts) throws IOException {
        var newest = posts
            .stream()
            .filter(p -> p.publishedAt() != null)
            .limit(out.perRss)
            .toList();
        var path = base + FEED_SEGMENT + "/";
        var last = newest.isEmpty() ? Instant.EPOCH : newest.getFirst().publishedAt().toInstant(ZoneOffset.UTC);
        out.page(
                path,
                render(
                        "feed/rss2.xml",
                        "site",
                        site,
                        "items",
                        fragments(FragmentRepository.FEED, newest, v -> render("feed/rss2-item.xml", "item", v)),
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
                var rel = "media/" + media.relativize(src).toString().replace('\\', '/');
                // an incremental release already holds the live one's media
                if (!Files.exists(out.root.resolve(rel))) {
                    out.link(rel, src);
                }
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

    /**
     * Nanoseconds per named phase, summed over repeats, in first-seen order.
     */
    private static final class Phases {
        private final Map<String, Long> nanos = new LinkedHashMap<>();

        void add(String name, long startNanos) {
            nanos.merge(name, System.nanoTime() - startNanos, Long::sum);
        }

        @Override
        public String toString() {
            return nanos
                .entrySet()
                .stream()
                .map(e -> e.getKey() + " " + e.getValue() / 1_000_000 + " ms")
                .collect(Collectors.joining(", "));
        }
    }

    private static final class Out {
        final Phases phases = new Phases();
        final Path root;
        /**
         * The release untouched files are linked from; null in a full publish.
         */
        final Path live;
        final PublishQueue.Batch batch;
        final PublishStatus status;
        /**
         * Listing bases to render in an incremental publish; filled once the walk knows them.
         */
        Set<String> dirtyBases = Set.of();
        /**
         * {@code published_item} rows for the rendered items.
         */
        List<PublishedItemRepository.Row> rows = List.of();
        int files;
        /**
         * The comments feed is hard-linked from the live release: the batch has no approved comment to change it.
         */
        boolean commentsFeedCarried;
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
        /**
         * Directories already created in this release, so each is made once rather than once per file.
         */
        private final Set<Path> dirs = new HashSet<>();

        Out(Path root, Path live, PublishQueue.Batch batch, PublishStatus status) {
            this.root = root;
            this.live = live;
            this.batch = batch;
            this.status = status;
        }

        boolean wantsItem(long id) {
            return live == null || batch.items().contains(id);
        }

        boolean wantsBase(String base) {
            return live == null || dirtyBases.contains(base);
        }

        /**
         * A file carried over unchanged from the live release; not counted as written.
         */
        void hardLink(String relative, Path source) throws IOException {
            var target = root.resolve(relative);
            mkdirs(target.getParent());
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(source, target);
            }
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
            hardLink(relative, source);
            status.progress(++files);
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

        private void mkdirs(Path dir) throws IOException {
            // shortcut: add-then-create is sequential-only; with threads, create first, then record
            if (dirs.add(dir)) {
                Files.createDirectories(dir);
            }
        }

        private void write(Path target, byte[] bytes) throws IOException {
            claim(target);
            mkdirs(target.getParent());
            // never write through a link: the same inode is a file of the live release
            Files.deleteIfExists(target);
            Files.write(target, bytes);
            status.progress(++files);
        }
    }
}
