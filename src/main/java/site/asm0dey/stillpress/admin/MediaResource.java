package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import io.quarkus.security.identity.SecurityIdentity;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.MediaService;
import site.asm0dey.stillpress.db.tables.records.MediaRecord;
import site.asm0dey.stillpress.store.MediaRepository;
import site.asm0dey.stillpress.wpapi.OEmbedProxy;

/**
 * Media lookups and the embed preview the editor needs; any logged-in user.
 */
@Path("/admin/api")
@Authenticated
public class MediaResource {
    @Inject
    MediaRepository media;
    @Inject
    OEmbedProxy oembed;
    @Inject
    MediaService service;
    @Inject
    SecurityIdentity identity;

    public record MediaView(
            long id,
            String url,
            String mimeType,
            Integer width,
            Integer height,
            String title,
            String alt
    ) {}

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        return Response.status(e.status).entity(Map.of("error", e.error)).build();
    }

    public record Meta(String title, String alt) {}

    public record Library(List<MediaView> items, int total) {}

    @ServerExceptionMapper
    public Response notAllowed(IllegalArgumentException e) {
        return Response.status(400).entity(Map.of("error", e.getMessage())).build();
    }

    @GET
    @Path("media/{id}")
    public MediaView get(@PathParam("id") long id) {
        return media
            .findById(id)
            .map(MediaResource::view)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
    }

    @GET
    @Path("media")
    public Library list(
            @QueryParam("type") String type,
            @QueryParam("search") String search,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("perPage") @DefaultValue("40") int perPage
    ) {
        var p = media.list(type, search, page, Math.clamp(perPage, 1, 100));
        return new Library(p.items().stream().map(MediaResource::view).toList(), p.total());
    }

    @POST
    @Path("media")
    public Response upload(@RestForm FileUpload file) {
        var actor = ItemsResource.actor(identity);
        if (!Capabilities.canUpload(actor.role())) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        if (file == null) {
            return Response.status(400).entity(Map.of("error", "file is required")).build();
        }
        return Response
            .status(201)
            .entity(view(service.store(file.fileName(), file.uploadedFile(), actor.userId())))
            .build();
    }

    @PUT
    @Path("media/{id}")
    public MediaView update(@PathParam("id") long id, Meta meta) {
        if (!Capabilities.canUpload(ItemsResource.actor(identity).role())) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        return view(service.updateMeta(id, meta.title(), meta.alt()));
    }

    private static MediaView view(MediaRecord r) {
        return new MediaView(
                r.getId(),
                r.getPath(),
                r.getMimeType(),
                r.getWidth(),
                r.getHeight(),
                r.getTitle(),
                r.getAltText()
        );
    }

    @GET
    @Path("oembed")
    public Response oembed(@QueryParam("url") String url) {
        return oembed.fetch(url);
    }
}
