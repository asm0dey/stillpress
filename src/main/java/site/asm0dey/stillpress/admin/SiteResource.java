package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import site.asm0dey.stillpress.store.CommentRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * What the dashboard shows about the site.
 */
@Path("/admin/api/site")
public class SiteResource {
    @Inject
    SettingRepository settings;
    @Inject
    ItemRepository items;
    @Inject
    CommentRepository comments;

    public record Site(String title, String homeUrl, String language, int items, int comments) {}

    @GET
    @Authenticated
    public Site site() {
        return new Site(
                settings.get("title").orElse(""),
                settings.get("home_url").orElse(""),
                settings.get("language").orElse("en"),
                items.count(),
                comments.count()
        );
    }
}
