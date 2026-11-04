package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import site.asm0dey.stillpress.db.tables.records.TermRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
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
}
