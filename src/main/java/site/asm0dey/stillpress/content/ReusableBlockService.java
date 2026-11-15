package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import module java.base;
import site.asm0dey.stillpress.blocks.BlockRenderer;
import site.asm0dey.stillpress.db.tables.pojos.ReusableBlock;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

/**
 * Saving reusable blocks: capabilities, optimistic concurrency, revisions, and republishing the published items that
 * show the block. Revisions and autosaves of a block are keyed by its public id ({@code source_id}).
 */
@ApplicationScoped
public class ReusableBlockService {
    public static final String OBJECT_TYPE = "reusable_block";
    private final ReusableBlockRepository blocks;
    private final Revisions revisions;
    private final PublishQueue queue;
    private final Jobs jobs;
    private final ImportGuard guard;
    private final HtmlCheck html;

    ReusableBlockService(
            ReusableBlockRepository blocks,
            Revisions revisions,
            PublishQueue queue,
            Jobs jobs,
            ImportGuard guard,
            HtmlCheck html
    ) {
        this.blocks = blocks;
        this.revisions = revisions;
        this.queue = queue;
        this.jobs = jobs;
        this.guard = guard;
        this.html = html;
    }

    /**
     * Creates ({@code ref == null}) or updates a block; a null title or content keeps the stored value. Refused with
     * 403 past the actor's capabilities, 404 for an unknown ref, 409 when the block changed since
     * {@code expectedModifiedAt}.
     */
    @Transactional
    public ReusableBlock save(
            Long ref,
            String title,
            String content,
            LocalDateTime expectedModifiedAt,
            ItemService.Actor a
    ) {
        return ref == null ? create(title, content, a) : update(ref, title, content, expectedModifiedAt, a).block();
    }

    /**
     * A saved block and how many published items its save queued for republishing.
     */
    public record Saved(ReusableBlock block, int republished) {}

    /**
     * {@link #save} of an existing block, with the number of items it republishes.
     */
    @Transactional
    public Saved update(long ref, String title, String content, LocalDateTime expectedModifiedAt, ItemService.Actor a) {
        var r = editable(ref, a);
        if (!Objects.equals(r.modifiedAt(), expectedModifiedAt)) {
            throw new ItemService.Refused(409, "conflict", null);
        }
        // the items showing the block are published with the new content
        html.block(a.role(), ref, title, content, () -> blocks.itemsUsing(withContainers(ref)));
        guard.close();
        r = blocks.update(r.id(), title != null ? title : r.title(), content != null ? content : r.content(), now());
        revisions.saved(OBJECT_TYPE, ref, r.title(), r.content(), "blocks", null, a.userId());
        revisions.deleteAutosave(OBJECT_TYPE, ref, a.userId());
        // the publish run drops the queued items' fragments (SitePublisher.run: fragments.delete(batch.items()))
        var published = blocks
            .itemsUsing(withContainers(ref))
            .stream()
            .filter(u -> "publish".equals(u.status()))
            .map(u -> u.itemId())
            .toList();
        queue.items(published);
        if (!published.isEmpty()) {
            jobs.requestPublish();
        }
        return new Saved(r, published.size());
    }

    @Transactional
    public void autosave(long ref, ItemService.Actor a, String title, String content) {
        editable(ref, a);
        html.block(a.role(), ref, title, content, List::of);
        guard.close();
        revisions.autosave(OBJECT_TYPE, ref, a.userId(), title, content, null);
    }

    /**
     * An ordinary {@link #save} with the revision's title and content.
     */
    @Transactional
    public Saved restoreRevision(long ref, long revisionId, ItemService.Actor a) {
        var current = editable(ref, a).modifiedAt();
        var rev = revisions
            .find(revisionId)
            .filter(v -> OBJECT_TYPE.equals(v.objectType()) && v.objectId() == ref && !v.autosave())
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        return update(ref, rev.title(), rev.body(), current, a);
    }

    private ReusableBlock create(String title, String content, ItemService.Actor a) {
        if (!Capabilities.canCreateBlock(a.role())) {
            throw forbidden();
        }
        html.block(a.role(), null, title, content, List::of);
        guard.close();
        var r = blocks.create(
                Objects.requireNonNullElse(title, ""),
                Objects.requireNonNullElse(content, ""),
                a.userId(),
                now()
        );
        // items may already show the new public id; a refusal rolls the insert back
        var ref = r.sourceId();
        html.block(a.role(), ref, title, content, () -> blocks.itemsUsing(withContainers(ref)));
        revisions.saved(OBJECT_TYPE, r.sourceId(), r.title(), r.content(), "blocks", null, a.userId());
        return r;
    }

    /**
     * The block and every block that shows it, up to the depth the renderer follows.
     */
    private Set<Long> withContainers(long ref) {
        var contains = blocks
            .all()
            .stream()
            .collect(Collectors.toMap(b -> b.sourceId(), b -> ReusableBlockRepository.refsIn(b.content())));
        var affected = new HashSet<>(Set.of(ref));
        var frontier = Set.of(ref);
        for (int depth = 0; depth < BlockRenderer.MAX_DEPTH && !frontier.isEmpty(); depth++) {
            var reached = frontier;
            frontier = contains
                .entrySet()
                .stream()
                .filter(e -> !affected.contains(e.getKey()) && e.getValue().stream().anyMatch(reached::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            affected.addAll(frontier);
        }
        return affected;
    }

    private ReusableBlock editable(long ref, ItemService.Actor a) {
        // the lock keeps two saves (or autosaves) of one block from interleaving
        var r = blocks
            .lock(ref)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        if (!Capabilities.canEditBlock(a.role(), a.userId(), r)) {
            throw forbidden();
        }
        return r;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static ItemService.Refused forbidden() {
        return new ItemService.Refused(403, "forbidden", null);
    }
}
