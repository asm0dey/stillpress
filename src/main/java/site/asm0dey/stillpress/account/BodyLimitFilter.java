package site.asm0dey.stillpress.account;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import module java.base;

/**
 * The 1 GiB body limit is for the WXR and media uploads only: every other admin API request is small JSON, so a larger body is
 * refused before anything reads it. A chunked body has no length to check and is refused too; browsers send one.
 */
@ApplicationScoped
public class BodyLimitFilter {
    static final long MAX_JSON_BODY = 64 * 1024;

    void register(@Observes Filters filters) {
        filters.register(ctx -> {
            var req = ctx.request();
            var path = ctx.normalizedPath();
            if (path != null
                    && path.startsWith("/admin/api/")
                    && !path.equals("/admin/api/import")
                    && !path.equals("/admin/api/media")) {
                if (req.getHeader("Transfer-Encoding") != null) {
                    refuse(ctx.response(), 411, "Content-Length required");
                    return;
                }
                var length = req.getHeader("Content-Length");
                if (length != null && tooLong(length)) {
                    refuse(ctx.response(), 413, "Request body too large");
                    return;
                }
            }
            ctx.next();
        }, 110);
    }

    private static boolean tooLong(String length) {
        try {
            return Long.parseLong(length.strip()) > MAX_JSON_BODY;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static void refuse(HttpServerResponse response, int status, String message) {
        // the unread body stays on the connection, so it is closed rather than reused
        response
            .setStatusCode(status)
            .putHeader("Content-Type", "text/plain; charset=UTF-8")
            .putHeader("Connection", "close")
            .end(message);
    }
}
