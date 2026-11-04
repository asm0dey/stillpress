package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.content.ItemService;
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

    @GET
    @Path("media/{id}")
    public MediaView get(@PathParam("id") long id) {
        return media
            .findById(id)
            .map(r -> new MediaView(
                    r.getId(),
                    r.getPath(),
                    r.getMimeType(),
                    r.getWidth(),
                    r.getHeight(),
                    r.getTitle(),
                    r.getAltText()
            ))
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
    }

    @GET
    @Path("oembed")
    public Response oembed(@QueryParam("url") String url) {
        return oembed.fetch(url);
    }
}
