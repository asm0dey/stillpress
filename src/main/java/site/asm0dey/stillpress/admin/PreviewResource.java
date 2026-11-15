package site.asm0dey.stillpress.admin;

import io.quarkus.qute.Engine;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RevisionRepository;

/**
 * An item rendered as the site would show it, from this user's autosave when that is newer; never written out.
 */
@Path("/admin/preview")
@Authenticated
public class PreviewResource {
    private final ItemRepository items;
    private final RevisionRepository revisions;
    private final ViewFactory views;
    private final Engine engine;
    private final SecurityIdentity identity;

    PreviewResource(
            ItemRepository items,
            RevisionRepository revisions,
            ViewFactory views,
            Engine engine,
            SecurityIdentity identity
    ) {
        this.items = items;
        this.revisions = revisions;
        this.views = views;
        this.engine = engine;
        this.identity = identity;
    }

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
        revisions.overlayAutosave(r, a.userId());
        var html = engine
            .getTemplate("plain/single")
            .data("site", views.site())
            .data("item", views.item(r))
            .render();
        return Response.ok(html).header("Cache-Control", "no-store").header("X-Robots-Tag", "noindex").build();
    }
}
