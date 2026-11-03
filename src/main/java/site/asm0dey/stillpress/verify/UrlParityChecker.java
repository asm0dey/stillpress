package site.asm0dey.stillpress.verify;

import module java.base;
import module java.net.http;
import module java.xml;
import java.time.Duration;
import org.jsoup.Jsoup;
import site.asm0dey.stillpress.verify.ParityReport.Mismatch;

/**
 * Cutover check #1: every source URL must resolve equivalently on the target.
 */
public class UrlParityChecker {
    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_HOPS = 10;
    private static final List<String> SKIP = List.of("/wp-admin", "/wp-login.php", "/wp-json", "/xmlrpc.php");
    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
    private final Duration sourceDelay;

    public UrlParityChecker() {
        this(Duration.ofMillis(200));
    }

    public UrlParityChecker(Duration sourceDelay) {
        this.sourceDelay = sourceDelay;
    }

    private record Resp(int status, URI location, String body, String contentType) {}

    /**
     * Outcome of following redirects: first status, last status and URI, or an error.
     */
    private record Chain(int first, int last, URI finalUri, String error, String body, String contentType) {}

    public ParityReport check(URI source, URI target, int maxUrls) {
        var paths = collect(source, maxUrls);
        var mismatches = new ArrayList<Mismatch>();
        for (var p : paths) {
            var s = follow(URI.create(root(source) + p), true);
            var t = follow(URI.create(root(target) + p), false);
            var detail = compare(s, t);
            if (detail != null) {
                mismatches.add(new Mismatch(p, s.first(), t.first(), detail));
            }
        }
        return new ParityReport(paths.size(), mismatches);
    }

    private static String compare(Chain s, Chain t) {
        if (t.error() != null) {
            return "target: " + t.error();
        }
        if (t.first() == 200) {
            return null;
        }
        if (t.first() == 301 || t.first() == 302 || t.first() == 308) {
            if (t.last() != 200) {
                return "redirect ends in " + t.last() + " at " + t.finalUri();
            }
            if (s.error() != null) {
                return "source: " + s.error();
            }
            if (!samePathAndQuery(s.finalUri(), t.finalUri())) {
                return "redirects to "
                        + t.finalUri().getRawPath()
                        + " but source resolves to "
                        + s.finalUri().getRawPath();
            }
            return null;
        }
        return "target returned " + t.first();
    }

    private static boolean samePathAndQuery(URI a, URI b) {
        return Objects.equals(a.getPath(), b.getPath()) && Objects.equals(a.getRawQuery(), b.getRawQuery());
    }

    private static String root(URI u) {
        return u.getScheme() + "://" + u.getRawAuthority();
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
    }

    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static String pathQuery(URI u) {
        var p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
    }

    private Resp send(URI uri, boolean delay) throws IOException, InterruptedException {
        if (delay && !sourceDelay.isZero()) {
            Thread.sleep(sourceDelay);
        }
        var res = client.send(
                HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", UA).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        var loc = res.headers().firstValue("Location").map(uri::resolve).orElse(null);
        return new Resp(res.statusCode(), loc, res.body(), res.headers().firstValue("Content-Type").orElse(""));
    }

    private Chain follow(URI start, boolean delay) {
        var uri = start;
        try {
            int first = -1;
            for (int hop = 0; hop <= MAX_HOPS; hop++) {
                var r = send(uri, delay);
                if (hop == 0) {
                    first = r.status();
                }
                if (r.status() < 300 || r.status() >= 400 || r.location() == null) {
                    return new Chain(first, r.status(), uri, null, r.body(), r.contentType());
                }
                if (!sameHost(start, r.location())) {
                    return new Chain(first, r.status(), uri, "cross-host redirect to " + r.location(), null, "");
                }
                uri = r.location();
            }
            return new Chain(first, -1, uri, "more than " + MAX_HOPS + " redirects", null, "");
        } catch (IOException | RuntimeException e) {
            return new Chain(-1, -1, uri, e.getClass().getSimpleName() + ": " + e.getMessage(), null, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Chain(-1, -1, uri, "interrupted", null, "");
        }
    }

    // ---- source URL collection ----
    private Set<String> collect(URI source, int max) {
        var paths = new LinkedHashSet<String>();
        var seenMaps = new HashSet<URI>();
        for (var sm : List.of("/wp-sitemap.xml", "/sitemap_index.xml")) {
            sitemap(URI.create(root(source) + sm), source, paths, seenMaps, max);
        }
        crawl(source, paths, max);
        return paths;
    }

    private void sitemap(URI map, URI source, Set<String> paths, Set<URI> seen, int max) {
        if (!seen.add(map)) {
            return;
        }
        var c = follow(map, true);
        if (c.error() != null || c.last() != 200 || c.body() == null) {
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
                if (!name.equals("loc")) {
                    continue;
                }
                var loc = URI.create(rd.getElementText().trim());
                if (!sameHost(source, loc)) {
                    continue;
                }
                if ("sitemapindex".equals(rootName)) {
                    sitemap(loc, source, paths, seen, max);
                } else if (paths.size() < max) {
                    paths.add(pathQuery(loc));
                }
            }
        } catch (XMLStreamException | IllegalArgumentException e) {
            // malformed sitemap: treat as absent
        }
    }

    private void crawl(URI source, Set<String> paths, int max) {
        var queue = new ArrayDeque<String>(List.of("/"));
        var visited = new HashSet<String>(queue);
        while (!queue.isEmpty()) {
            var p = queue.poll();
            var c = follow(URI.create(root(source) + p), true);
            if (c.error() != null || c.last() != 200 || !c.contentType().contains("html") || c.body() == null) {
                continue;
            }
            if (p.equals("/") && paths.size() < max) {
                paths.add(p);
            }
            for (var a : Jsoup.parse(c.body(), c.finalUri().toString()).select("a[href]")) {
                var href = a.attr("href").trim();
                if (href.startsWith("#")) {
                    continue;
                }
                URI link;
                try {
                    link = c.finalUri().resolve(href);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                var scheme = link.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    continue;
                }
                if (!sameHost(source, link)) {
                    continue;
                }
                var pq = pathQuery(link);
                if (SKIP.stream().anyMatch(pq::contains) || !visited.add(pq)) {
                    continue;
                }
                if (paths.size() >= max) {
                    return;
                }
                paths.add(pq);
                queue.add(pq);
            }
        }
    }
}
