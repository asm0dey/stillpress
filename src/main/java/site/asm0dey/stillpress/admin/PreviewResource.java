package site.asm0dey.stillpress.admin;

import io.quarkus.qute.Engine;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.content.Revisions;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import site.asm0dey.stillpress.store.ItemRepository;

/**
 * An item rendered as the site would show it, from this user's autosave when that is newer; never written out.
 */
@Path("/admin/preview")
@Authenticated
public class PreviewResource {
    @Inject
    ItemRepository items;
    @Inject
    Revisions revisions;
    @Inject
    ViewFactory views;
    @Inject
    Engine engine;
    @Inject
    SecurityIdentity identity;

    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response preview(@PathParam("id") long id) {
        var a = ItemsResource.actor(identity);
        var r = items.find(id).orElse(null);
        if (r == null) {
            return Response.status(404).build();
        }
        if (!Capabilities.canEdit(a.role(), a.userId(), r)) {
            return Response.status(403).build();
        }
        // the record is changed in memory only and never stored
        revisions
            .autosaveOf("item", id, a.userId())
            .filter(s -> r.getModifiedAt() == null || s.getCreatedAt().isAfter(r.getModifiedAt()))
            .ifPresent(s -> {
                r.setTitle(s.getTitle());
                r.setBody(s.getBody());
                r.setExcerpt(s.getExcerpt());
            });
        var html = engine
            .getTemplate("plain/single")
            .data("site", views.site())
            .data("item", views.item(r))
            .render();
        return Response.ok(html).header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").build();
    }
}
