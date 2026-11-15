package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.COMMENT;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.pojos.Comment;

@ApplicationScoped
public class CommentRepository {
    private final DSLContext db;

    CommentRepository(DSLContext db) {
        this.db = db;
    }

    public int count() {
        return db.fetchCount(COMMENT);
    }

    /**
     * Approved comments of an item, oldest first.
     */
    public List<Comment> approvedFor(long itemId) {
        return db
            .selectFrom(COMMENT)
            .where(COMMENT.ITEM_ID.eq(itemId), COMMENT.STATUS.eq("approved"))
            .orderBy(COMMENT.CREATED_AT, COMMENT.ID)
            .fetchInto(Comment.class);
    }

    /**
     * Whether any of the items has a comment row, whatever its status.
     */
    public boolean anyComment(Collection<Long> itemIds) {
        return db.fetchExists(COMMENT, COMMENT.ITEM_ID.in(itemIds));
    }

    /**
     * Newest approved comments on published, non-password-protected posts and pages.
     */
    public List<Comment> newestPublic(int limit) {
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
            .fetchInto(Comment.class);
    }

    /**
     * An imported comment; returns its id. The parent is set apart, by {@link #setParent}.
     */
    public long insertImported(Comment c) {
        var r = db.newRecord(COMMENT);
        r.setSourceId(c.sourceId());
        r.setItemId(c.itemId());
        r.setAuthorName(c.authorName());
        r.setAuthorEmail(c.authorEmail());
        r.setAuthorUrl(c.authorUrl());
        r.setContent(c.content());
        r.setCreatedAt(c.createdAt());
        r.setStatus(c.status());
        r.store();
        return r.getId();
    }

    public void setParent(long id, long parentId) {
        db.update(COMMENT).set(COMMENT.PARENT_ID, parentId).where(COMMENT.ID.eq(id)).execute();
    }
}
