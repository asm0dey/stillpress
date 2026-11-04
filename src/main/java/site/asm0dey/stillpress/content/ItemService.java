package site.asm0dey.stillpress.content;

import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Item rules shared by the editor and the background jobs.
 */
@ApplicationScoped
public class ItemService {
    @Inject
    DSLContext db;
    @Inject
    SettingRepository settings;

    /**
     * Gives {@code r} its slug and permalink as WordPress would on first publish: posts from the site's
     * {@code permalink_structure}, pages from their ancestors' slugs. A permalink another item has already moves the
     * slug on to {@code -2}, {@code -3}... Only sets the fields; the caller stores the record.
     */
    public void assignPermalink(ItemRecord r) {
        var base = r.getSlug() == null || r.getSlug().isBlank()
                ? Slugs.title(Objects.requireNonNullElse(r.getTitle(), ""))
                : r.getSlug();
        if (base.isEmpty()) {
            base = String.valueOf(r.getSourceId());
        }
        var slug = base;
        var permalink = permalink(r, slug);
        for (int n = 2; taken(permalink, r.getId()); n++) {
            slug = base + "-" + n;
            permalink = permalink(r, slug);
        }
        r.setSlug(slug);
        r.setPermalink(permalink);
    }

    private boolean taken(String permalink, Long id) {
        return db.fetchExists(ITEM, ITEM.PERMALINK.eq(permalink), id == null ? ITEM.ID.isNotNull() : ITEM.ID.ne(id));
    }

    private String permalink(ItemRecord r, String slug) {
        if ("page".equals(r.getType())) {
            var parts = new ArrayDeque<String>();
            parts.addFirst(slug);
            var seen = new HashSet<Long>();
            for (var p = r.getParentId(); p != null && seen.add(p); ) {
                var parent = db.select(ITEM.SLUG, ITEM.PARENT_ID).from(ITEM).where(ITEM.ID.eq(p)).fetchOne();
                if (parent == null) {
                    break;
                }
                parts.addFirst(parent.get(ITEM.SLUG));
                p = parent.get(ITEM.PARENT_ID);
            }
            return "/" + String.join("/", parts) + "/";
        }
        var structure = settings.get("permalink_structure").orElse(PermalinkStructure.DEFAULT);
        var local = r.getPublishedLocal() != null ? r.getPublishedLocal() : localNow(r);
        var author = r.getAuthorId() == null
                ? null
                : db.select(AUTHOR.SLUG).from(AUTHOR).where(AUTHOR.ID.eq(r.getAuthorId())).fetchOne(AUTHOR.SLUG);
        return PermalinkStructure.render(
                structure,
                new PermalinkStructure.Fields(
                        Objects.requireNonNullElse(r.getSourceId(), 0L),
                        slug,
                        local,
                        categoryPath(r.getId()),
                        Objects.requireNonNullElse(author, ""),
                        null
                )
        );
    }

    /**
     * {@code published_at} (UTC) in the site's time zone; now when the item has no date yet.
     */
    private LocalDateTime localNow(ItemRecord r) {
        var zone = ZoneId.of(settings.get("timezone").orElse("UTC"));
        var utc = r.getPublishedAt() != null ? r.getPublishedAt() : LocalDateTime.now(ZoneOffset.UTC);
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime();
    }

    /**
     * WordPress's {@code %category%}: the category with the lowest id, under its ancestors; {@code uncategorized}
     * when the post has none.
     */
    private String categoryPath(Long itemId) {
        var first = itemId == null
                ? null
                : db
            .select(TERM.ID)
            .from(TERM)
            .join(ITEM_TERM)
            .on(ITEM_TERM.TERM_ID.eq(TERM.ID))
            .where(ITEM_TERM.ITEM_ID.eq(itemId), TERM.TAXONOMY.eq("category"))
            .orderBy(TERM.ID)
            .limit(1)
            .fetchOne(TERM.ID);
        if (first == null) {
            return "uncategorized";
        }
        var parts = new ArrayDeque<String>();
        var seen = new HashSet<Long>();
        for (var t = first; t != null && seen.add(t); ) {
            var term = db.select(TERM.SLUG, TERM.PARENT_ID).from(TERM).where(TERM.ID.eq(t)).fetchOne();
            if (term == null) {
                break;
            }
            parts.addFirst(term.get(TERM.SLUG));
            t = term.get(TERM.PARENT_ID);
        }
        return String.join("/", parts);
    }
}
