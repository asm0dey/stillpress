package site.asm0dey.stillpress.account;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import module java.base;

/**
 * CSRF guard: state-changing admin API requests must carry {@code X-Requested-With: stillpress}. No CORS is enabled,
 * so a cross-site browser request cannot set it.
 */
@ApplicationScoped
public class CsrfFilter {
    private static final Set<HttpMethod> STATE_CHANGING =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    void register(@Observes Filters filters) {
        filters.register(ctx -> {
            var req = ctx.request();
            // the decoded, dot-segment-free path the router matches, so /admin//api or /admin/%61pi cannot bypass
            var path = ctx.normalizedPath();
            if (path != null
                    && path.startsWith("/admin/api/")
                    && STATE_CHANGING.contains(req.method())
                    && !"stillpress".equals(req.getHeader("X-Requested-With"))) {
                ctx
                    .response()
                    .setStatusCode(403)
                    .putHeader("Content-Type", "text/plain; charset=UTF-8")
                    .end("Missing X-Requested-With");
                return;
            }
            ctx.next();
        }, 100);
    }
}
