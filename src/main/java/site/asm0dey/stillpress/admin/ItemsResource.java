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
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.Revisions;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.db.tables.records.RevisionRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * The editor's items, revisions, autosaves and terms. Any logged-in user; {@link ItemService} enforces capabilities.
 */
@Path("/admin/api")
@Authenticated
public class ItemsResource {
    private static final int PER_PAGE = 20;
    @Inject
    ItemService service;
    @Inject
    ItemRepository items;
    @Inject
    TermRepository terms;
    @Inject
    AuthorRepository authors;
    @Inject
    Revisions revisions;
    @Inject
    SecurityIdentity identity;

    public record ItemView(
            long id,
            String type,
            String title,
            String slug,
            String status,
            String body,
            String bodyFormat,
            String excerpt,
            LocalDateTime date,
            String permalink,
            List<Long> categories,
            List<Long> tags,
            Long authorId,
            Long parentId,
            String commentStatus,
            Integer menuOrder,
            LocalDateTime modifiedAt,
            AutosaveRef autosave
    ) {}

    public record AutosaveRef(LocalDateTime createdAt) {}

    public record ItemList(List<ItemRepository.Row> items, int total, Map<String, Integer> counts) {}

    public record NewItem(String type) {}

    public record Draft(String title, String body, String excerpt) {}

    public record AutosaveView(String title, String body, String excerpt, LocalDateTime createdAt) {}

    public record RevisionLine(long id, String author, LocalDateTime createdAt) {}

    public record RevisionView(
            long id,
            String title,
            String body,
            String excerpt,
            LocalDateTime createdAt,
            String author
    ) {}

    public record TermView(long id, String name, String slug, Long parentId) {}

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
    @Path("items")
    public ItemList list(
            @QueryParam("type") @DefaultValue("post") String type,
            @QueryParam("status") String status,
            @QueryParam("mine") boolean mine,
            @QueryParam("search") String search,
            @QueryParam("page") @DefaultValue("1") int page
    ) {
        if (!Set.of("post", "page").contains(type)) {
            throw new ItemService.Refused(400, "invalid", "type");
        }
        var a = actor();
        var ownOnly = mine || a.role() == Role.AUTHOR || a.role() == Role.CONTRIBUTOR;
        var filter = new ItemRepository.Filter(
                type,
                status == null || status.equals("any") ? null : status,
                ownOnly ? a.userId() : null,
                search
        );
        return new ItemList(
                items.list(filter, (Math.max(page, 1) - 1) * PER_PAGE, PER_PAGE),
                items.count(filter),
                items.countsByStatus(filter)
        );
    }

    @POST
    @Path("items")
    public Response create(NewItem n) {
        var a = actor();
        var type = n == null ? null : n.type();
        if (!Set.of("post", "page").contains(type)) {
            throw new ItemService.Refused(400, "invalid", "type");
        }
        if (!Capabilities.canCreate(a.role(), type)) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        return Response.status(201).entity(view(service.create(type, a.userId()), a)).build();
    }

    @GET
    @Path("items/{id}")
    public ItemView get(@PathParam("id") long id) {
        var a = actor();
        return view(viewable(id, a), a);
    }

    @PUT
    @Path("items/{id}")
    public ItemView update(@PathParam("id") long id, ItemService.Update u) {
        var a = actor();
        if (u == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        return view(service.update(id, u, a), a);
    }

    @DELETE
    @Path("items/{id}")
    public Response trash(@PathParam("id") long id) {
        service.trash(id, actor());
        return Response.noContent().build();
    }

    @POST
    @Path("items/{id}/restore")
    public ItemView restore(@PathParam("id") long id) {
        var a = actor();
        service.restore(id, a);
        return view(items.find(id).orElseThrow(), a);
    }

    @PUT
    @Path("items/{id}/autosave")
    public Response autosave(@PathParam("id") long id, Draft d) {
        if (d == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        service.autosave(id, actor(), d.title(), d.body(), d.excerpt());
        return Response.noContent().build();
    }

    @GET
    @Path("items/{id}/autosave")
    public AutosaveView autosave(@PathParam("id") long id) {
        var a = actor();
        viewable(id, a);
        return revisions
            .autosaveOf("item", id, a.userId())
            .map(r -> new AutosaveView(r.getTitle(), r.getBody(), r.getExcerpt(), r.getCreatedAt()))
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
    }

    @GET
    @Path("items/{id}/revisions")
    public List<RevisionLine> revisions(@PathParam("id") long id) {
        viewable(id, actor());
        var names = authorNames();
        return revisions
            .list("item", id)
            .stream()
            .map(r -> new RevisionLine(r.getId(), names.get(r.getAuthorId()), r.getCreatedAt()))
            .toList();
    }

    @GET
    @Path("revisions/{rid}")
    public RevisionView revision(@PathParam("rid") long rid) {
        // shortcut: item revisions only; reusable blocks get theirs with the block editor
        var r = revisions
            .find(rid)
            .filter(v -> "item".equals(v.getObjectType()) && !v.getAutosave())
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        viewable(r.getObjectId(), actor());
        return new RevisionView(
                r.getId(),
                r.getTitle(),
                r.getBody(),
                r.getExcerpt(),
                r.getCreatedAt(),
                authorNames().get(r.getAuthorId())
        );
    }

    @POST
    @Path("items/{id}/revisions/{rid}/restore")
    public ItemView restoreRevision(@PathParam("id") long id, @PathParam("rid") long rid) {
        var a = actor();
        return view(service.restoreRevision(id, rid, a), a);
    }

    @GET
    @Path("terms")
    public List<TermView> terms(@QueryParam("taxonomy") @DefaultValue("category") String taxonomy) {
        return terms
            .byTaxonomy(taxonomy)
            .stream()
            .map(t -> new TermView(t.getId(), t.getName(), t.getSlug(), t.getParentId()))
            .toList();
    }

    @POST
    @Path("terms")
    public Response createTerm(NewTerm n) {
        if (n == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        var t = service.createTerm(n.taxonomy(), n.name(), n.parentId(), actor());
        return Response
            .status(201)
            .entity(new TermView(t.getId(), t.getName(), t.getSlug(), t.getParentId()))
            .build();
    }

    private ItemRecord viewable(long id, ItemService.Actor a) {
        var r = items
            .find(id)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        if (!Capabilities.canView(a.role(), a.userId(), r)) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        return r;
    }

    private ItemView view(ItemRecord r, ItemService.Actor a) {
        var byTaxonomy = terms
            .forItem(r.getId())
            .stream()
            .collect(Collectors.groupingBy(
                    t -> t.getTaxonomy(),
                    Collectors.mapping(t -> t.getId(), Collectors.toList())
            ));
        var autosave = revisions
            .autosaveOf("item", r.getId(), a.userId())
            .map(RevisionRecord::getCreatedAt)
            .filter(at -> r.getModifiedAt() == null || at.isAfter(r.getModifiedAt()))
            .map(AutosaveRef::new)
            .orElse(null);
        return new ItemView(
                r.getId(),
                r.getType(),
                r.getTitle(),
                r.getSlug(),
                r.getStatus(),
                r.getBody(),
                r.getBodyFormat(),
                r.getExcerpt(),
                service.localDate(r),
                r.getPermalink(),
                byTaxonomy.getOrDefault("category", List.of()),
                byTaxonomy.getOrDefault("post_tag", List.of()),
                r.getAuthorId(),
                r.getParentId(),
                r.getCommentStatus(),
                r.getMenuOrder(),
                r.getModifiedAt(),
                autosave
        );
    }

    private Map<Long, String> authorNames() {
        return authors
            .all()
            .stream()
            .collect(Collectors.toMap(AuthorRecord::getId, u -> Objects.requireNonNullElse(
                    u.getDisplayName(),
                    u.getLogin()
            )));
    }

    private ItemService.Actor actor() {
        return actor(identity);
    }

    static ItemService.Actor actor(SecurityIdentity identity) {
        long id = identity.getAttribute(SessionIdentityProvider.USER_ID);
        return new ItemService.Actor(id, Role.of(identity.getRoles().iterator().next()));
    }
}
