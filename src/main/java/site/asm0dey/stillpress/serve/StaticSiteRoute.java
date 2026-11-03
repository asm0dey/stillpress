package site.asm0dey.stillpress.serve;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import module java.base;
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
                redirects
                    .find("?" + k + "=" + v)
                    .ifPresentOrElse(r -> redirect(ctx, r.getStatusCode(), Permalinks.encode(r.getToPath())), () -> notFound(
                            ctx,
                            root
                    ));
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
            var target = root.resolve(path.substring(1));
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
        redirects
            .find(path)
            .ifPresentOrElse(r -> redirect(ctx, r.getStatusCode(), Permalinks.encode(r.getToPath())), () -> notFound(
                    ctx,
                    root
            ));
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
        var res = ctx.response().setStatusCode(status);
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
                int hi = Character.digit(raw.charAt(i + 1), 16), lo = Character.digit(raw.charAt(i + 2), 16);
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
        for (var seg : path.split("/", -1)) {
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("dot segment");
            }
        }
        return path;
    }
}
