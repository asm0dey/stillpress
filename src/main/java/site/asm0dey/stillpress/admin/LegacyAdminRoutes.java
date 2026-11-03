package site.asm0dey.stillpress.admin;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * WordPress admin bookmarks land on the Stillpress admin.
 */
@ApplicationScoped
public class LegacyAdminRoutes {
    void init(@Observes Router router) {
        for (var path : new String[] {"/wp-admin", "/wp-admin/*", "/wp-login.php"}) {
            router
                .route(path)
                .handler(ctx -> ctx.response().setStatusCode(302).putHeader("Location", "/admin/").end());
        }
    }
}
