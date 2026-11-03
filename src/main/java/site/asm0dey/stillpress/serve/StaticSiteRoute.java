package site.asm0dey.stillpress.serve;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Permalinks;
import site.asm0dey.stillpress.publish.SitePublisher;
import site.asm0dey.stillpress.store.RedirectRepository;

/**
 * Serves the live release ({@code current}) with WordPress-compatible redirects. Registered last, so every other route
 * (REST, {@code /q/*}, later {@code /admin}) wins.
 */
@ApplicationScoped
public class StaticSiteRoute {
    static final String SHORT_CACHE = "public, max-age=300";
    static final String LONG_CACHE = "public, max-age=31536000, immutable";
    private static final List<String> SHORTLINK_KEYS = List.of("p", "page_id", "attachment_id");
    private static final Logger LOG = Logger.getLogger(StaticSiteRoute.class);
    @Inject
    DataDir dataDir;
    @Inject
    RedirectRepository redirects;

    void init(@Observes Router router) {
        // after every application route, but ahead of the dev-mode "/" -> Dev UI welcome redirect (Integer.MAX_VALUE);
        // blocking: file-system checks and the redirect lookup hit the disk and the database
        router.route().order(Integer.MAX_VALUE - 1).blockingHandler(this::handle, false);
    }

    void handle(RoutingContext ctx) {
        var req = ctx.request();
        var rawPath = req.path();
        if (req.method() != HttpMethod.GET && req.method() != HttpMethod.HEAD
                || rawPath == null
                || rawPath.startsWith("/q/")) {
            ctx.next();
            return;
        }
        var root = liveRoot();
        for (var k : SHORTLINK_KEYS) {
            var v = req.getParam(k);
            if (v != null) {
                storedRedirect(ctx, root, "?" + k + "=" + v);
                return;
            }
        }
        String path;
        try {
            path = decodePath(rawPath);
        } catch (IllegalArgumentException e) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "text/plain; charset=UTF-8").end("Bad Request");
            return;
        }
        if (root != null) {
            // decodePath already refuses empty segments; this keeps resolve() relative and lexically inside regardless
            var target = root.resolve(path.replaceFirst("^/+", "")).normalize();
            if (!target.startsWith(root)) {
                notFound(ctx, root);
                return;
            }
            if (Files.isDirectory(target)) {
                var index = inside(root, target.resolve("index.html"));
                if (index != null) {
                    if (!path.endsWith("/")) {
                        var q = req.query();
                        redirect(ctx, 301, Permalinks.encode(path + "/") + (q == null ? "" : "?" + q));
                    } else {
                        send(ctx, path, index, 200);
                    }
                    return;
                }
            } else {
                var file = inside(root, target);
                if (file != null) {
                    send(ctx, path, file, 200);
                    return;
                }
            }
        }
        // WordPress answers /post/attachment and /post/attachment/ alike
        var key = !path.endsWith("/") && redirects.find(path).isEmpty() ? path + "/" : path;
        storedRedirect(ctx, root, key);
    }

    private void storedRedirect(RoutingContext ctx, Path root, String key) {
        var hit = redirects.find(key);
        if (hit.isEmpty()) {
            notFound(ctx, root);
            return;
        }
        var to = hit.get().getToPath();
        // only a site-local, single-slash absolute path; anything else could send visitors off-site
        if (to == null || !to.startsWith("/") || to.startsWith("//") || to.indexOf('\\') >= 0) {
            LOG.warnf("Ignoring unsafe redirect target %s for %s", to, key);
            notFound(ctx, root);
            return;
        }
        redirect(ctx, hit.get().getStatusCode(), Permalinks.encode(to));
    }

    /**
     * Real path of the live release, or {@code null} before the first publish.
     */
    private Path liveRoot() {
        try {
            return dataDir.current().toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The file's real path when it is a regular file that, with every symlink resolved, stays under {@code root}.
     */
    private static Path inside(Path root, Path file) {
        try {
            var real = file.toRealPath();
            return real.startsWith(root) && Files.isRegularFile(real) ? real : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void send(RoutingContext ctx, String path, Path file, int status) {
        var res = ctx.response().setStatusCode(status).putHeader("X-Content-Type-Options", "nosniff");
        var name = file.getFileName().toString();
        if (name.equals("index.html")
                && file.getParent().getFileName().toString().equals(SitePublisher.FEED_SEGMENT)) {
            res.putHeader("Content-Type", "application/rss+xml; charset=UTF-8");
        } else if (name.endsWith(".html")) {
            res.putHeader("Content-Type", "text/html; charset=UTF-8");
        } else if (name.endsWith(".txt")) {
            res.putHeader("Content-Type", "text/plain; charset=UTF-8");
        }
        if (status == 200) {
            res.putHeader(
                    "Cache-Control",
                    path.startsWith("/media/") || path.startsWith("/assets/") ? LONG_CACHE : SHORT_CACHE
            );
        }
        // other content types come from the extension inside sendFile
        res.sendFile(file.toString());
    }

    private static void redirect(RoutingContext ctx, int status, String location) {
        if (!location.startsWith("/") || location.startsWith("//")) {
            throw new IllegalStateException("refusing non-local Location " + location);
        }
        ctx.response().setStatusCode(status).putHeader("Location", location).end();
    }

    private static void notFound(RoutingContext ctx, Path root) {
        var page = root == null ? null : inside(root, root.resolve("404.html"));
        if (page != null) {
            send(ctx, "/404.html", page, 404);
        } else {
            ctx.response().setStatusCode(404).putHeader("Content-Type", "text/plain; charset=UTF-8").end("Not Found");
        }
    }

    /**
     * Strict, single-pass percent-decoding of a raw request path as UTF-8 ({@code %XX} in either case; bytes above
     * 0x7f arrive as ISO-8859-1 chars from the request line). Throws on malformed escapes or UTF-8, an encoded
     * {@code /} or {@code \}, a backslash, NUL, or a {@code .}/{@code ..} segment.
     */
    static String decodePath(String raw) {
        if (!raw.startsWith("/")) {
            throw new IllegalArgumentException("not path-absolute");
        }
        var bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw new IllegalArgumentException("truncated escape");
                }
                int hi = hex(raw.charAt(i + 1)), lo = hex(raw.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("bad escape");
                }
                int b = hi << 4 | lo;
                if (b == '/' || b == '\\') {
                    throw new IllegalArgumentException("encoded separator");
                }
                bytes.write(b);
                i += 2;
            } else if (c > 0xff) {
                throw new IllegalArgumentException("non-byte char");
            } else {
                bytes.write(c);
            }
        }
        String path;
        try {
            path = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("bad UTF-8", e);
        }
        if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("NUL or backslash");
        }
        var segs = path.split("/", -1);
        for (int i = 1; i < segs.length; i++) {
            if (segs[i].equals(".") || segs[i].equals("..")) {
                throw new IllegalArgumentException("dot segment");
            }
            // only the final segment may be empty (the trailing slash); "//" would make the path absolute
            if (segs[i].isEmpty() && i < segs.length - 1) {
                throw new IllegalArgumentException("empty segment");
            }
        }
        return path;
    }

    /**
     * ASCII hex digit value, or -1; unlike {@link Character#digit} it refuses non-ASCII digits.
     */
    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
