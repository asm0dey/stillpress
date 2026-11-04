package site.asm0dey.stillpress.serve;

import io.quarkus.qute.Engine;
import module java.base;
import site.asm0dey.stillpress.publish.SitePublisher;

/**
 * The server-level nginx include for serving Mode 2 (ADR 0004): nginx serves the release, Stillpress the rest.
 */
public final class NginxConfig {
    /**
     * Paths always answered by Stillpress; a path with a file extension is matched exactly, the rest as prefixes.
     */
    static final List<String> DYNAMIC_PREFIXES = List.of("/admin", "/wp-admin", "/wp-login.php");
    static final List<String> UPLOAD_PATHS = List.of("/admin/api/import", "/admin/api/media");
    private static final String SMALL_BODY = "1m";
    private static final String LARGE_BODY = "1g";

    private NginxConfig() {
    }

    static String render(Path root, String upstream) {
        var proxied = new ArrayList<Map<String, String>>();
        proxied.add(Map.of("path", "@stillpress", "limit", SMALL_BODY));
        // exact matches win over the ^~ /admin prefix: only the two uploads may be large
        for (var p : UPLOAD_PATHS) {
            proxied.add(Map.of("path", "= " + p, "limit", LARGE_BODY));
        }
        for (var p : DYNAMIC_PREFIXES) {
            proxied.add(Map.of("path", (p.endsWith(".php") ? "= " : "^~ ") + p, "limit", SMALL_BODY));
        }
        try (var in = NginxConfig.class.getResourceAsStream("/templates/nginx/stillpress.conf")) {
            return Engine
                .builder()
                .addDefaults()
                .build()
                .parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .data("root", root)
                .data("upstream", upstream)
                .data("proxied", proxied)
                .data("feed", SitePublisher.FEED_SEGMENT)
                .data("shortCache", StaticSiteRoute.SHORT_CACHE)
                .data("longCache", StaticSiteRoute.LONG_CACHE)
                .render();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
