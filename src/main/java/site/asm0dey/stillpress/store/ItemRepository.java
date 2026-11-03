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
     * Published posts and pages, newest first.
     */
    public List<ItemRecord> findPublished() {
        return db
            .selectFrom(ITEM)
            .where(ITEM.STATUS.eq("publish"), ITEM.TYPE.in("post", "page"))
            .orderBy(ITEM.PUBLISHED_AT.desc())
            .fetch();
    }

    public Optional<ItemRecord> findByPermalink(String decodedPath) {
        return db.selectFrom(ITEM).where(ITEM.PERMALINK.eq(decodedPath)).fetchOptional();
    }

    public int count() {
        return db.fetchCount(ITEM);
    }
}
