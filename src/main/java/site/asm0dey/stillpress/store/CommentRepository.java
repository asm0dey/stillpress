package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.COMMENT;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.CommentRecord;

@ApplicationScoped
public class CommentRepository {
    @Inject
    DSLContext db;

    public int count() {
        return db.fetchCount(COMMENT);
    }

    /**
     * Approved comments of an item, oldest first.
     */
    public List<CommentRecord> approvedFor(long itemId) {
        return db
            .selectFrom(COMMENT)
            .where(COMMENT.ITEM_ID.eq(itemId), COMMENT.STATUS.eq("approved"))
            .orderBy(COMMENT.CREATED_AT, COMMENT.ID)
            .fetch();
    }

    /**
     * Newest approved comments on published, non-password-protected posts and pages.
     */
    public List<CommentRecord> newestPublic(int limit) {
        return db
            .select(COMMENT.fields())
            .from(COMMENT)
            .join(ITEM)
            .on(ITEM.ID.eq(COMMENT.ITEM_ID))
            .where(
                    COMMENT.STATUS.eq("approved"),
                    ITEM.STATUS.eq("publish"),
                    ITEM.TYPE.in("post", "page"),
                    ITEM.PASSWORD_PROTECTED.isFalse()
            )
            .orderBy(COMMENT.CREATED_AT.desc().nullsLast(), COMMENT.ID.desc())
            .limit(limit)
            .fetchInto(COMMENT);
    }
}
