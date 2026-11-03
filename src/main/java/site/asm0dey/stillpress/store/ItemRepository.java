package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.ITEM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;

@ApplicationScoped
public class ItemRepository {
    @Inject
    DSLContext db;

    /**
     * Published, non-password-protected posts and pages, newest first.
     */
    public List<ItemRecord> findPublished() {
        return db
            .selectFrom(ITEM)
            .where(ITEM.STATUS.eq("publish"), ITEM.TYPE.in("post", "page"), ITEM.PASSWORD_PROTECTED.isFalse())
            .orderBy(ITEM.PUBLISHED_AT.desc())
            .fetch();
    }

    public Optional<ItemRecord> findByPermalink(String decodedPath) {
        return db.selectFrom(ITEM).where(ITEM.PERMALINK.eq(decodedPath)).fetchOptional();
    }

    public int count() {
        return db.fetchCount(ITEM);
    }

    /**
     * An item without its body: enough to place it in archives and feeds without holding content in memory.
     * {@code publishedAt} is UTC (feeds); {@code publishedLocal} is the site-local time (date archives), UTC when the
     * export had no {@code wp:post_date}.
     */
    public record Summary(
            long id,
            String type,
            String permalink,
            LocalDateTime publishedAt,
            LocalDateTime publishedLocal,
            Long authorId
    ) {}

    /**
     * Published, non-password-protected posts and pages, newest first (undated last).
     */
    public List<Summary> findPublishedSummaries() {
        return db
            .select(ITEM.ID, ITEM.TYPE, ITEM.PERMALINK, ITEM.PUBLISHED_AT, ITEM.PUBLISHED_LOCAL, ITEM.AUTHOR_ID)
            .from(ITEM)
            .where(ITEM.STATUS.eq("publish"), ITEM.TYPE.in("post", "page"), ITEM.PASSWORD_PROTECTED.isFalse())
            .orderBy(ITEM.PUBLISHED_AT.desc().nullsLast(), ITEM.ID.desc())
            .fetch(r -> new Summary(
                    r.value1(),
                    r.value2(),
                    Objects.requireNonNullElse(r.value3(), "/"),
                    r.value4(),
                    r.value5() != null ? r.value5() : r.value4(),
                    r.value6()
            ));
    }

    /**
     * Full records for the given ids, in the order of {@code ids}.
     */
    public List<ItemRecord> findByIds(List<Long> ids) {
        var byId = new HashMap<Long, ItemRecord>();
        db
            .selectFrom(ITEM)
            .where(ITEM.ID.in(ids))
            .forEach(r -> byId.put(r.getId(), r));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
