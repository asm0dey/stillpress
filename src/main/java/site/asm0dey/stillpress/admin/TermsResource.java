package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.TermService;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Categories and tags. Any logged-in user lists them and authors create them from the editor; {@link TermService}
 * lets only editors and administrators edit or delete.
 */
@Path("/admin/api/terms")
@Authenticated
public class TermsResource {
    @Inject
    TermService service;
    @Inject
    TermRepository terms;
    @Inject
    SecurityIdentity identity;

    public record TermView(long id, String name, String slug, Long parentId, String description) {}

    public record NewTerm(String taxonomy, String name, Long parentId) {}

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        var body = new LinkedHashMap<String, String>();
        body.put("error", e.error);
        if (e.field != null) {
            body.put("field", e.field);
        }
        return Response.status(e.status).entity(body).build();
    }

    @GET
    public List<TermRepository.Counted> list(@QueryParam("taxonomy") @DefaultValue("category") String taxonomy) {
        return terms.byTaxonomyCounted(taxonomy);
    }

    @POST
    public Response create(NewTerm n) {
        if (n == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        var t = service.createTerm(n.taxonomy(), n.name(), n.parentId(), actor());
        return Response.status(201).entity(view(t)).build();
    }

    @PUT
    @Path("{id}")
    public TermView update(@PathParam("id") long id, TermService.Change c) {
        return view(service.updateTerm(id, c, actor()));
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") long id) {
        service.deleteTerm(id, actor());
        return Response.noContent().build();
    }

    private static TermView view(TermRecord t) {
        return new TermView(t.getId(), t.getName(), t.getSlug(), t.getParentId(), t.getDescription());
    }

    private ItemService.Actor actor() {
        return ItemsResource.actor(identity);
    }
}
