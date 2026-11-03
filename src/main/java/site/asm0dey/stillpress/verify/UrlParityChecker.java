package site.asm0dey.stillpress.verify;

import module java.base;
import module java.net.http;
import module java.xml;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jsoup.Jsoup;
import site.asm0dey.stillpress.publish.Permalinks;
import site.asm0dey.stillpress.verify.ParityReport.Cause;
import site.asm0dey.stillpress.verify.ParityReport.Mismatch;

/**
 * Cutover check #1: every source URL must resolve equivalently on the target.
 */
public class UrlParityChecker {
    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(60);
    private static final int MAX_HOPS = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final List<String> SKIP = List.of("/wp-admin", "/wp-login.php", "/wp-json", "/xmlrpc.php");
    private static final List<String> SEED_FEEDS = List.of("/feed/", "/comments/feed/");
    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
    private final Duration sourceDelay;
    private final Duration retryBase;

    public UrlParityChecker() {
        this(Duration.ofMillis(200), Duration.ofSeconds(1));
    }

    public UrlParityChecker(Duration sourceDelay, Duration retryBase) {
        this.sourceDelay = sourceDelay;
        this.retryBase = retryBase;
    }

    private enum Fail {
        NONE,
        CROSS_HOST,
        LOOP,
        NETWORK
    }

    private record Resp(int status, URI location, String body, String contentType, String retryAfter) {}

    /**
     * Outcome of following redirects: first status, last status and URI, or a failure.
     */
    private record Chain(int first, int last, URI finalUri, Fail fail, String error, String body, String contentType) {
        Chain withoutBody() {
            return new Chain(first, last, finalUri, fail, error, null, contentType);
        }

        boolean sourceFailed() {
            return fail != Fail.NONE || last == 403 || last == 429 || last == 503;
        }

        String sourceFailure() {
            return fail != Fail.NONE ? error : "source returned " + last;
        }
    }

    public ParityReport check(URI source, URI target, int maxUrls) {
        return new Run(source, target, maxUrls).run();
    }

    // ---- redirect and URI helpers ----
    /**
     * Uploads are assets: Stillpress serves them under /media/, so their URLs may change (spec section 1).
     */
    static boolean isUpload(String pathQuery) {
        var q = pathQuery.indexOf('?');
        return Permalinks.decode(q < 0 ? pathQuery : pathQuery.substring(0, q)).startsWith("/wp-content/uploads/");
    }

    static boolean sameSite(URI a, URI b) {
        return a.getHost() != null
                && b.getHost() != null
                && hostKey(a).equals(hostKey(b))
                && (port(a) == port(b) || (defaultPort(port(a)) && defaultPort(port(b))));
    }

    private static String hostKey(URI u) {
        var h = u.getHost().toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static boolean defaultPort(int p) {
        return p == 80 || p == 443;
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
    }

    private static String root(URI u) {
        return u.getScheme() + "://" + u.getRawAuthority();
    }

    private static String pathQuery(URI u) {
        var p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
    }

    private static boolean samePathAndQuery(URI a, URI b) {
        return Objects.equals(a.getPath(), b.getPath()) && Objects.equals(a.getRawQuery(), b.getRawQuery());
    }

    private static String describe(Exception e) {
        var detail = e.getMessage() != null ? e.getMessage() : e.getCause() != null ? e.getCause().toString() : null;
        return e.getClass().getSimpleName() + (detail == null ? "" : ": " + detail);
    }

    // ---- HTTP ----
    private static HttpResponse.BodyHandler<String> textOnly() {
        return info -> {
            var ct = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            return ct.contains("html") || ct.contains("xml")
                    ? HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
                    : HttpResponse.BodySubscribers.replacing("");
        };
    }

    private Duration retryWait(Resp r, int attempt) {
        var ra = r.retryAfter();
        Duration wait = null;
        if (ra != null) {
            try {
                wait = Duration.ofSeconds(Long.parseLong(ra.trim()));
            } catch (NumberFormatException e) {
                try {
                    wait = Duration.between(
                            ZonedDateTime.now(),
                            ZonedDateTime.parse(ra.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    );
                } catch (DateTimeParseException ignored) {
                    // fall through to backoff
                }
            }
        }
        if (wait == null) {
            wait = retryBase.multipliedBy(1L << (attempt - 1));
        }
        return wait.isNegative() ? Duration.ZERO : wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
    }

    private Resp send(URI uri, boolean isSource) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            if (isSource && !sourceDelay.isZero()) {
                Thread.sleep(sourceDelay);
            }
            var res =
                    client.send(
                            HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", UA).GET().build(),
                            textOnly()
            );
            var r = new Resp(
                    res.statusCode(),
                    res.headers().firstValue("Location").map(uri::resolve).orElse(null),
                    res.body(),
                    res.headers().firstValue("Content-Type").orElse(""),
                    res.headers().firstValue("Retry-After").orElse(null)
            );
            if (isSource && (r.status() == 429 || r.status() == 503) && attempt < MAX_ATTEMPTS) {
                Thread.sleep(retryWait(r, attempt));
                continue;
            }
            return r;
        }
    }

    private Chain follow(URI start, boolean isSource) {
        var uri = start;
        try {
            int first = -1;
            for (int hop = 0; hop <= MAX_HOPS; hop++) {
                var r = send(uri, isSource);
                if (hop == 0) {
                    first = r.status();
                }
                if (r.status() < 300 || r.status() >= 400 || r.location() == null) {
                    return new Chain(first, r.status(), uri, Fail.NONE, null, r.body(), r.contentType());
                }
                if (!sameSite(start, r.location())) {
                    return new Chain(
                            first,
                            r.status(),
                            uri,
                            Fail.CROSS_HOST,
                            "cross-host redirect to " + r.location(),
                            null,
                            ""
                    );
                }
                uri = r.location();
            }
            return new Chain(first, -1, uri, Fail.LOOP, "more than " + MAX_HOPS + " redirects", null, "");
        } catch (IOException | RuntimeException e) {
            return new Chain(-1, -1, uri, Fail.NETWORK, describe(e), null, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Chain(-1, -1, uri, Fail.NETWORK, "interrupted", null, "");
        }
    }

    // ---- one run ----
    private final class Run {
        final URI source, target;
        final int max;
        final Set<String> paths = new LinkedHashSet<>();
        final Map<String, Chain> sourceChains = new HashMap<>();
        final Map<String, Mismatch> bad = new LinkedHashMap<>();
        URI canonical;

        Run(URI source, URI target, int max) {
            this.source = source;
            this.target = target;
            this.max = max;
        }

        ParityReport run() {
            // The start URL's own redirect chain fixes the canonical origin (www/https moves).
            var start = follow(URI.create(root(source) + "/"), true);
            canonical = start.fail == Fail.NONE ? URI.create(root(start.finalUri())) : source;
            sourceChains.put("/", start);
            collect();
            for (var p : paths) {
                var s =
                        sourceChains
                    .computeIfAbsent(p, k -> follow(URI.create(root(canonical) + k), true))
                    .withoutBody();
                sourceChains.put(p, s);
                var m = compare(p, s, follow(URI.create(root(target) + p), false));
                if (m != null) {
                    bad.put(p, m);
                }
            }
            return new ParityReport(paths.size(), new ArrayList<>(bad.values()));
        }

        boolean inSite(URI u) {
            return sameSite(canonical, u) || sameSite(source, u);
        }

        void sourceError(String key, int status, String detail) {
            bad.put(key, new Mismatch(key, status, -1, detail, Cause.SOURCE_FETCH_FAILURE));
        }

        Mismatch compare(String p, Chain s, Chain t) {
            if (s.sourceFailed()) {
                return new Mismatch(p, s.last(), t.last(), s.sourceFailure(), Cause.SOURCE_FETCH_FAILURE);
            }
            if (t.fail != Fail.NONE) {
                var cause = switch (t.fail) {
                    case CROSS_HOST -> Cause.CROSS_HOST;
                    case LOOP -> Cause.REDIRECT_LOOP;
                    default -> Cause.NETWORK_ERROR;
                };
                return new Mismatch(p, s.last(), t.last(), t.error, cause);
            }
            if (s.last() == 200 && t.last() == 200) {
                return samePathAndQuery(s.finalUri(), t.finalUri())
                        ? null
                        : new Mismatch(
                                p,
                                s.last(),
                                t.last(),
                                "target ends at " + pathQuery(t.finalUri()) + " but source ends at " + pathQuery(s.finalUri()
                                ),
                                Cause.DIFFERENT_FINAL_PATH
                );
            }
            if (s.last() == t.last()) {
                return null;
            }
            return new Mismatch(
                    p,
                    s.last(),
                    t.last(),
                    "target ended with " + t.last() + ", source with " + s.last(),
                    t.last() == 404 ? Cause.TARGET_404 : Cause.TARGET_OTHER_STATUS
            );
        }

        void collect() {
            var seen = new HashSet<URI>();
            for (var sm : List.of("/wp-sitemap.xml", "/sitemap_index.xml")) {
                sitemap(URI.create(root(canonical) + sm), seen, true);
            }
            for (var f : SEED_FEEDS) {
                add(f);
            }
            crawl();
        }

        boolean add(String pq) {
            if (isUpload(pq)) {
                return true;
            }
            if (paths.size() >= max) {
                return false;
            }
            paths.add(pq);
            return true;
        }

        void sitemap(URI map, Set<URI> seen, boolean rootMap) {
            if (!seen.add(map)) {
                return;
            }
            var c = follow(map, true);
            var key = pathQuery(map);
            if (c.sourceFailed()) {
                sourceError(key, c.last(), "sitemap " + c.sourceFailure());
                return;
            }
            if (c.last() != 200 || c.body() == null) {
                return;
            }
            // A soft-404 page or text body at a root sitemap path just means "no sitemap here".
            if (rootMap && !c.contentType().toLowerCase(Locale.ROOT).contains("xml")) {
                return;
            }
            try {
                var f = XMLInputFactory.newInstance();
                f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                var rd = f.createXMLStreamReader(new StringReader(c.body()));
                String rootName = null;
                while (rd.hasNext()) {
                    if (rd.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    var name = rd.getLocalName();
                    if (rootName == null) {
                        rootName = name;
                    }
                    if (!name.equals("loc") || !SITEMAP_NS.equals(rd.getNamespaceURI())) {
                        continue;
                    }
                    var text = rd.getElementText().trim();
                    URI loc;
                    try {
                        loc = new URI(text);
                    } catch (URISyntaxException e) {
                        sourceError(key + "#" + text, -1, "bad <loc> in " + key + ": " + describe(e));
                        continue;
                    }
                    if (!inSite(loc)) {
                        continue;
                    }
                    if ("sitemapindex".equals(rootName)) {
                        sitemap(URI.create(root(canonical) + pathQuery(loc)), seen, false);
                    } else {
                        add(pathQuery(loc));
                    }
                }
            } catch (XMLStreamException e) {
                sourceError(key, -1, "malformed sitemap " + key + ": " + describe(e));
            }
        }

        void crawl() {
            var queue = new ArrayDeque<String>(List.of("/"));
            var queued = new HashSet<String>(queue);
            while (!queue.isEmpty()) {
                var p = queue.poll();
                var c = sourceChains.computeIfAbsent(p, k -> follow(URI.create(root(canonical) + k), true));
                if (c.sourceFailed()) {
                    sourceError(p, c.last(), c.sourceFailure());
                    continue;
                }
                if (c.last() != 200) {
                    continue;
                }
                if (p.equals("/")) {
                    add(p);
                }
                var html = c.body() != null && c.contentType().toLowerCase(Locale.ROOT).contains("html");
                var doc = html ? Jsoup.parse(c.body(), c.finalUri().toString()) : null;
                sourceChains.put(p, c.withoutBody());
                if (doc == null) {
                    continue;
                }
                for (var l : doc.select("link[rel=alternate][href]")) {
                    var type = l.attr("type");
                    if (type.equals("application/rss+xml") || type.equals("application/atom+xml")) {
                        var pq = linkPath(c.finalUri(), l.attr("href"));
                        if (pq != null) {
                            add(pq);
                        }
                    }
                }
                for (var a : doc.select("a[href]")) {
                    var pq = linkPath(c.finalUri(), a.attr("href"));
                    if (pq == null || !queued.add(pq)) {
                        continue;
                    }
                    if (paths.size() >= max && !paths.contains(pq)) {
                        return;
                    }
                    add(pq);
                    queue.add(pq);
                }
            }
        }

        /**
         * Same-site, crawlable link as path+query, or null.
         */
        String linkPath(URI base, String href) {
            href = href.trim();
            if (href.startsWith("#")) {
                return null;
            }
            URI link;
            try {
                link = base.resolve(href);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (!"http".equals(link.getScheme()) && !"https".equals(link.getScheme())) {
                return null;
            }
            if (!inSite(link)) {
                return null;
            }
            var pq = pathQuery(link);
            if (isUpload(pq)) {
                return null;
            }
            return SKIP.stream().anyMatch(pq::contains) ? null : pq;
        }
    }
}
