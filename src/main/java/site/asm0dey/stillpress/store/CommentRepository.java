package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.COMMENT;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.CommentRecord;

@ApplicationScoped
public class CommentRepository {
    @Inject
    DSLContext db;

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
}
