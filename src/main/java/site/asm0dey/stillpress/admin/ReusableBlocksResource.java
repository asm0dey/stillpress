package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.ReusableBlockService;
import site.asm0dey.stillpress.content.Revisions;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;
import site.asm0dey.stillpress.db.tables.records.ReusableBlockRecord;
import site.asm0dey.stillpress.db.tables.records.RevisionRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

/**
 * The editor's reusable blocks, their revisions and autosaves. Any logged-in user may read them;
 * {@link ReusableBlockService} enforces who may write.
 */
@Path("/admin/api/blocks")
@Authenticated
public class ReusableBlocksResource {
    private static final String TYPE = ReusableBlockService.OBJECT_TYPE;
    @Inject
    ReusableBlockService service;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    AuthorRepository authors;
    @Inject
    Revisions revisions;
    @Inject
    SecurityIdentity identity;

    public record BlockLine(long id, String title, String author, LocalDateTime modifiedAt, int usedBy, boolean canEdit) {}

    public record BlockView(
            long id,
            String title,
            String content,
            String author,
            LocalDateTime modifiedAt,
            int usedBy,
            boolean canEdit,
            ItemsResource.AutosaveRef autosave
    ) {}

    public record BlockSave(String title, String content, LocalDateTime expectedModifiedAt) {}

    public record Draft(String title, String content) {}

    public record AutosaveView(String title, String content, LocalDateTime createdAt) {}

    public record RevisionView(long id, String title, String content, LocalDateTime createdAt, String author) {}

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
    public List<BlockLine> list() {
        var a = actor();
        var all = blocks.all();
        var usedBy = usedBy(all.stream().map(ReusableBlockRecord::getSourceId).collect(Collectors.toSet()));
        var names = authorNames();
        return all
            .stream()
            .map(b -> new BlockLine(
                    b.getSourceId(),
                    b.getTitle(),
                    names.get(b.getAuthorId()),
                    b.getModifiedAt(),
                    usedBy.getOrDefault(b.getSourceId(), 0),
                    Capabilities.canEditBlock(a.role(), a.userId(), b)
            ))
            .toList();
    }

    @GET
    @Path("{id}")
    public BlockView get(@PathParam("id") long id) {
        return view(find(id), actor());
    }

    @POST
    public Response create(BlockSave s) {
        if (s == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        var a = actor();
        return Response.status(201).entity(view(service.save(null, s.title(), s.content(), null, a), a)).build();
    }

    @PUT
    @Path("{id}")
    public BlockView update(@PathParam("id") long id, BlockSave s) {
        if (s == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        var a = actor();
        return view(service.save(id, s.title(), s.content(), s.expectedModifiedAt(), a), a);
    }

    @PUT
    @Path("{id}/autosave")
    public Response autosave(@PathParam("id") long id, Draft d) {
        if (d == null) {
            throw new ItemService.Refused(400, "invalid", null);
        }
        service.autosave(id, actor(), d.title(), d.content());
        return Response.noContent().build();
    }

    @GET
    @Path("{id}/autosave")
    public AutosaveView autosave(@PathParam("id") long id) {
        find(id);
        return revisions
            .autosaveOf(TYPE, id, actor().userId())
            .map(r -> new AutosaveView(r.getTitle(), r.getBody(), r.getCreatedAt()))
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
    }

    @GET
    @Path("{id}/revisions")
    public List<ItemsResource.RevisionLine> revisions(@PathParam("id") long id) {
        find(id);
        var names = authorNames();
        return revisions
            .list(TYPE, id)
            .stream()
            .map(r -> new ItemsResource.RevisionLine(r.getId(), names.get(r.getAuthorId()), r.getCreatedAt()))
            .toList();
    }

    @GET
    @Path("{id}/revisions/{rid}")
    public RevisionView revision(@PathParam("id") long id, @PathParam("rid") long rid) {
        var r = revisions
            .find(rid)
            .filter(v -> TYPE.equals(v.getObjectType()) && v.getObjectId() == id && !v.getAutosave())
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        return new RevisionView(
                r.getId(),
                r.getTitle(),
                r.getBody(),
                r.getCreatedAt(),
                authorNames().get(r.getAuthorId())
        );
    }

    @POST
    @Path("{id}/revisions/{rid}/restore")
    public BlockView restoreRevision(@PathParam("id") long id, @PathParam("rid") long rid) {
        var a = actor();
        return view(service.restoreRevision(id, rid, a), a);
    }

    private ReusableBlockRecord find(long id) {
        return blocks
            .find(id)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
    }

    private BlockView view(ReusableBlockRecord b, ItemService.Actor a) {
        var autosave = revisions
            .autosaveOf(TYPE, b.getSourceId(), a.userId())
            .map(RevisionRecord::getCreatedAt)
            .filter(at -> b.getModifiedAt() == null || at.isAfter(b.getModifiedAt()))
            .map(ItemsResource.AutosaveRef::new)
            .orElse(null);
        return new BlockView(
                b.getSourceId(),
                b.getTitle(),
                b.getContent(),
                b.getAuthorId() == null ? null : authorNames().get(b.getAuthorId()),
                b.getModifiedAt(),
                usedBy(Set.of(b.getSourceId())).getOrDefault(b.getSourceId(), 0),
                Capabilities.canEditBlock(a.role(), a.userId(), b),
                autosave
        );
    }

    /**
     * Ref to the number of items (not trashed) whose body uses it directly.
     */
    private Map<Long, Integer> usedBy(Set<Long> refs) {
        var counts = new HashMap<Long, Integer>();
        blocks
            .itemsUsing(refs)
            .forEach(u -> u.refs().forEach(r -> counts.merge(r, 1, Integer::sum)));
        return counts;
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
        return ItemsResource.actor(identity);
    }
}
