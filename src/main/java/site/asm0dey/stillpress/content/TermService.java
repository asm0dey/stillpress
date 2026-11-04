package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * Categories and tags created in the admin.
 */
@ApplicationScoped
public class TermService {
    @Inject
    TermRepository terms;
    @Inject
    SettingRepository settings;
    @Inject
    Permalinker permalinks;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;
    @Inject
    ImportGuard guard;
    @Inject
    FragmentRepository fragments;
    @Inject
    RedirectRepository redirects;

    /**
     * A new category or tag; its empty archive is published.
     */
    @Transactional
    public TermRecord createTerm(String taxonomy, String name, Long parentId, ItemService.Actor a) {
        guard.close();
        if (!Capabilities.canCreateTerm(a.role())) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        var category = "category".equals(taxonomy);
        if (!category && !"post_tag".equals(taxonomy)) {
            throw new ItemService.Refused(400, "invalid", "taxonomy");
        }
        if (name == null || Slugs.title(name).isEmpty()) {
            throw new ItemService.Refused(400, "invalid", "name");
        }
        if (parentId != null
                && (!category || terms.findById(parentId).filter(p -> "category".equals(p.getTaxonomy())).isEmpty())) {
            throw new ItemService.Refused(400, "invalid", "parentId");
        }
        var t = terms.create(taxonomy, name.strip(), parentId);
        var base = settings.get(category ? "category_base" : "tag_base").orElse(category ? "category" : "tag");
        queue.base("/" + base + "/" + (category ? permalinks.termPath(t.getId()) : t.getSlug()) + "/");
        jobs.requestPublish();
        return t;
    }

    /**
     * A back-office edit of a term. A blank {@code slug} is made from {@code name}.
     */
    public record Change(String name, String slug, Long parentId, String description) {}

    /**
     * Renames, reslugs or moves a term. A moved archive (the term's and every descendant's) gets a 301 from its old
     * base; the old and new bases and every published item in the moved terms are republished, since item pages show
     * term links.
     */
    @Transactional
    public TermRecord updateTerm(long id, Change c, ItemService.Actor a) {
        if (!Capabilities.canManageTerms(a.role())) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        var t = terms
            .findById(id)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        if (c == null || c.name() == null || Slugs.title(c.name()).isEmpty()) {
            throw new ItemService.Refused(400, "invalid", "name");
        }
        var slug = Slugs.title(c.slug() == null || c.slug().isBlank() ? c.name() : c.slug());
        if (slug.isEmpty()) {
            throw new ItemService.Refused(400, "invalid", "slug");
        }
        var tree = tree(t.getTaxonomy());
        var moved = subtree(tree, id);
        if (c.parentId() != null
                && (!"category".equals(t.getTaxonomy())
                || !tree.containsKey(c.parentId())
                || moved.contains(c.parentId()))) {
            throw new ItemService.Refused(400, "invalid", "parentId");
        }
        if (terms
            .findBySlug(t.getTaxonomy(), slug)
            .filter(o -> o.getId() != id)
            .isPresent()) {
            throw new ItemService.Refused(409, "slug_taken", "slug");
        }
        guard.close();
        var name = c.name().strip();
        var before = bases(tree, moved);
        terms.update(id, name, slug, c.parentId(), c.description() == null ? null : c.description().strip());
        var after = bases(tree(t.getTaxonomy()), moved);
        var shown = new HashSet<Long>();
        if (!t.getName().equals(name)) {
            shown.add(id);
        }
        queue.base(after.get(id));
        queue.base(before.get(id));
        shown.addAll(move(before, after));
        republish(shown);
        jobs.requestPublish();
        return terms.findById(id).orElseThrow();
    }

    /**
     * Deletes a term, never the default category. Its children move up to its parent (their archives move, with
     * redirects); its posts lose it, and a post left with no category gets the default one.
     */
    @Transactional
    public void deleteTerm(long id, ItemService.Actor a) {
        if (!Capabilities.canManageTerms(a.role())) {
            throw new ItemService.Refused(403, "forbidden", null);
        }
        var t = terms
            .findById(id)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        if (settings.get("default_category").filter(String.valueOf(id)::equals).isPresent()) {
            throw new ItemService.Refused(409, "default_category", null);
        }
        guard.close();
        var tree = tree(t.getTaxonomy());
        var under = subtree(tree, id);
        var before = bases(tree, under);
        var had = terms.itemIds(List.of(id), false);
        var published = new HashSet<>(terms.itemIds(under, true));
        under.remove(id);
        terms.delete(id, t.getParentId());
        queue.base(before.get(id));
        before.remove(id);
        move(before, bases(tree(t.getTaxonomy()), under));
        if ("category".equals(t.getTaxonomy())) {
            permalinks
                .ensureCategory(had)
                .ifPresent(d -> queue.base(base("category", path(tree(t.getTaxonomy()), d))));
        }
        fragments.delete(published);
        queue.items(published);
        jobs.requestPublish();
    }

    /**
     * 301s and queued bases for every term whose base moved; returns those terms.
     */
    private Set<Long> move(Map<Long, String> before, Map<Long, String> after) {
        var moves = new LinkedHashMap<String, String>();
        var changed = new HashSet<Long>();
        before.forEach((term, old) -> {
            var now = after.get(term);
            if (!old.equals(now)) {
                moves.put(old, now);
                changed.add(term);
                queue.base(old);
                queue.base(now);
            }
        });
        redirects.putAll(moves);
        return changed;
    }

    /**
     * Item pages and archive entries show term links: the published items of {@code termIds} and their descendants
     * are re-rendered.
     */
    private void republish(Set<Long> termIds) {
        if (termIds.isEmpty()) {
            return;
        }
        var ids = terms.itemIds(termIds, true);
        fragments.delete(ids);
        queue.items(ids);
    }

    private Map<Long, TermRecord> tree(String taxonomy) {
        var byId = new HashMap<Long, TermRecord>();
        terms
            .byTaxonomy(taxonomy)
            .forEach(r -> byId.put(r.getId(), r));
        return byId;
    }

    /**
     * {@code id} and all its descendants.
     */
    private static Set<Long> subtree(Map<Long, TermRecord> tree, long id) {
        var out = new LinkedHashSet<Long>();
        out.add(id);
        for (boolean grew = true; grew; ) {
            grew = false;
            for (var r : tree.values()) {
                if (r.getParentId() != null && out.contains(r.getParentId()) && out.add(r.getId())) {
                    grew = true;
                }
            }
        }
        return out;
    }

    private Map<Long, String> bases(Map<Long, TermRecord> tree, Set<Long> ids) {
        var out = new HashMap<Long, String>();
        ids.forEach(id -> out.put(id, base(tree.get(id).getTaxonomy(), path(tree, id))));
        return out;
    }

    private static String path(Map<Long, TermRecord> tree, long id) {
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (
                var t = tree.get(id);
                t != null && seen.add(t.getId());
                t = t.getParentId() == null ? null : tree.get(t.getParentId())) {
            parts.addFirst(t.getSlug());
        }
        return String.join("/", parts);
    }

    private String base(String taxonomy, String path) {
        var category = "category".equals(taxonomy);
        return "/"
                + settings.get(category ? "category_base" : "tag_base").orElse(category ? "category" : "tag")
                + "/"
                + path
                + "/";
    }
}
