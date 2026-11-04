package site.asm0dey.stillpress.admin;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * WordPress admin bookmarks land on the Stillpress admin, and so does {@code /admin} without the slash (Quinoa serves
 * only {@code /admin/}). The admin's HTML shell is revalidated on every load, so an upgrade never leaves a browser on a
 * shell whose hashed bundles are gone.
 */
@ApplicationScoped
public class LegacyAdminRoutes {
    void init(@Observes Router router) {
        for (var path : new String[] {"/wp-admin", "/wp-admin/*", "/wp-login.php"}) {
            router
                .route(path)
                .handler(ctx -> ctx.response().setStatusCode(302).putHeader("Location", "/admin/").end());
        }
        // Vert.x matches /admin/ here too; the exact check keeps that one for the SPA
        router.route("/admin").handler(ctx -> {
            if (!"/admin".equals(ctx.normalizedPath())) {
                ctx.next();
                return;
            }
            var query = ctx.request().query();
            ctx.response().setStatusCode(302).putHeader("Location", query == null ? "/admin/" : "/admin/?" + query).end();
        });
        // the static handler marks everything immutable for a day (prod); only the hashed assets may keep that
        router.route("/admin/*").handler(ctx -> {
            var response = ctx.response();
            ctx.addHeadersEndHandler(v -> {
                var type = response.headers().get("Content-Type");
                var cache = response.headers().get("Cache-Control");
                if (type != null && type.startsWith("text/html") && (cache == null || cache.contains("immutable"))) {
                    response.putHeader("Cache-Control", "no-cache");
                }
            });
            ctx.next();
        });
    }
}
