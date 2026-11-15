package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.ReusableBlockService;
import site.asm0dey.stillpress.content.Revisions;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.ReusableBlock;
import site.asm0dey.stillpress.db.tables.pojos.Revision;
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
    private final ReusableBlockService service;
    private final ReusableBlockRepository blocks;
    private final AuthorRepository authors;
    private final Revisions revisions;
    private final SecurityIdentity identity;

    ReusableBlocksResource(
            ReusableBlockService service,
            ReusableBlockRepository blocks,
            AuthorRepository authors,
            Revisions revisions,
            SecurityIdentity identity
    ) {
        this.service = service;
        this.blocks = blocks;
        this.authors = authors;
        this.revisions = revisions;
        this.identity = identity;
    }

    /**
     * {@code content} only with {@code ?content=true} (the editor's inserter); the list screen leaves it out.
     */
    public record BlockLine(
            long id,
            String title,
            String content,
            String author,
            LocalDateTime modifiedAt,
            int usedBy,
            boolean canEdit
    ) {}

    public record BlockView(
            long id,
            String title,
            String content,
            String author,
            LocalDateTime modifiedAt,
            int usedBy,
            boolean canEdit,
            ItemsResource.AutosaveRef autosave,
            /* only in a PUT's answer: the published items that save queued for republishing */
            Integer republished
    ) {}

    public record BlockSave(String title, String content, LocalDateTime expectedModifiedAt) {}

    public record Draft(String title, String content) {}

    public record AutosaveView(String title, String content, LocalDateTime createdAt) {}

    public record RevisionView(long id, String title, String content, LocalDateTime createdAt, String author) {}

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        return Response.status(e.status).entity(e.body()).build();
    }

    @GET
    public List<BlockLine> list(@QueryParam("content") boolean content) {
        var a = actor();
        var all = blocks.all();
        var usedBy = usedBy(all.stream().map(ReusableBlock::sourceId).collect(Collectors.toSet()));
        var names = authorNames();
        return all
            .stream()
            .map(b -> new BlockLine(
                    b.sourceId(),
                    b.title(),
                    content ? b.content() : null,
                    names.get(b.authorId()),
                    b.modifiedAt(),
                    usedBy.getOrDefault(b.sourceId(), 0),
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
        if (s.expectedModifiedAt() == null) {
            throw new ItemService.Refused(400, "invalid", "expectedModifiedAt");
        }
        var a = actor();
        var saved = service.update(id, s.title(), s.content(), s.expectedModifiedAt(), a);
        return view(saved.block(), a, saved.republished());
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
            .map(r -> new AutosaveView(r.title(), r.body(), r.createdAt()))
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
            .map(r -> new ItemsResource.RevisionLine(r.id(), names.get(r.authorId()), r.createdAt()))
            .toList();
    }

    @GET
    @Path("{id}/revisions/{rid}")
    public RevisionView revision(@PathParam("id") long id, @PathParam("rid") long rid) {
        var r = revisions
            .find(rid)
            .filter(v -> TYPE.equals(v.objectType()) && v.objectId() == id && !v.autosave())
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        return new RevisionView(r.id(), r.title(), r.body(), r.createdAt(), authorNames().get(r.authorId()));
    }

    @POST
    @Path("{id}/revisions/{rid}/restore")
    public BlockView restoreRevision(@PathParam("id") long id, @PathParam("rid") long rid) {
        var a = actor();
        var saved = service.restoreRevision(id, rid, a);
        return view(saved.block(), a, saved.republished());
    }

    private ReusableBlock find(long id) {
        return blocks
            .find(id)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
    }

    private BlockView view(ReusableBlock b, ItemService.Actor a) {
        return view(b, a, null);
    }

    private BlockView view(ReusableBlock b, ItemService.Actor a, Integer republished) {
        var autosave = revisions
            .autosaveOf(TYPE, b.sourceId(), a.userId())
            .map(Revision::createdAt)
            .filter(at -> b.modifiedAt() == null || at.isAfter(b.modifiedAt()))
            .map(ItemsResource.AutosaveRef::new)
            .orElse(null);
        return new BlockView(
                b.sourceId(),
                b.title(),
                b.content(),
                b.authorId() == null ? null : authorNames().get(b.authorId()),
                b.modifiedAt(),
                usedBy(Set.of(b.sourceId())).getOrDefault(b.sourceId(), 0),
                Capabilities.canEditBlock(a.role(), a.userId(), b),
                autosave,
                republished
        );
    }

    /**
     * Ref to the number of items (not trashed) whose body uses it directly.
     */
    private Map<Long, Integer> usedBy(Set<Long> refs) {
        var counts = new HashMap<Long, Integer>();
        blocks
            .itemsUsing(refs)
            .stream()
            .filter(u -> !"trash".equals(u.status()))
            .forEach(u -> u.refs().forEach(r -> counts.merge(r, 1, Integer::sum)));
        return counts;
    }

    private Map<Long, String> authorNames() {
        return authors
            .all()
            .stream()
            .collect(Collectors.toMap(Author::id, u -> Objects.requireNonNullElse(u.displayName(), u.login())));
    }

    private ItemService.Actor actor() {
        return ItemsResource.actor(identity);
    }
}
