package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.REVISION;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.db.tables.records.RevisionRecord;

/**
 * Saved revisions and per-user autosaves of items and reusable blocks.
 */
@ApplicationScoped
public class RevisionRepository {
    @Inject
    DSLContext db;

    public void insert(
            String objectType,
            long objectId,
            String title,
            String body,
            String bodyFormat,
            String excerpt,
            long authorId,
            boolean autosave
    ) {
        db
            .insertInto(
                    REVISION,
                    REVISION.OBJECT_TYPE,
                    REVISION.OBJECT_ID,
                    REVISION.TITLE,
                    REVISION.BODY,
                    REVISION.BODY_FORMAT,
                    REVISION.EXCERPT,
                    REVISION.AUTHOR_ID,
                    REVISION.CREATED_AT,
                    REVISION.AUTOSAVE
            )
            .values(objectType, objectId, title, body, bodyFormat, excerpt, authorId, now(), autosave)
            .execute();
    }

    /**
     * Deletes all but the newest {@code keep} revisions of the object, in one statement.
     */
    public void prune(String objectType, long objectId, int keep) {
        var newest = DSL
            .select(REVISION.ID)
            .from(REVISION)
            .where(of(objectType, objectId), REVISION.AUTOSAVE.isFalse())
            .orderBy(REVISION.ID.desc())
            .limit(keep);
        db
            .deleteFrom(REVISION)
            .where(of(objectType, objectId), REVISION.AUTOSAVE.isFalse(), REVISION.ID.notIn(newest))
            .execute();
    }

    public void deleteAutosave(String objectType, long objectId, long userId) {
        db.deleteFrom(REVISION).where(autosaveOf(objectType, objectId, userId)).execute();
    }

    public Optional<RevisionRecord> autosave(String objectType, long objectId, long userId) {
        return db.selectFrom(REVISION).where(autosaveOf(objectType, objectId, userId)).fetchOptional();
    }

    /**
     * Newest first, without autosaves.
     */
    public List<RevisionRecord> list(String objectType, long objectId) {
        return db
            .selectFrom(REVISION)
            .where(of(objectType, objectId), REVISION.AUTOSAVE.isFalse())
            .orderBy(REVISION.ID.desc())
            .fetch();
    }

    public Optional<RevisionRecord> find(long id) {
        return db.selectFrom(REVISION).where(REVISION.ID.eq(id)).fetchOptional();
    }

    private static org.jooq.Condition of(String objectType, long objectId) {
        return REVISION.OBJECT_TYPE.eq(objectType).and(REVISION.OBJECT_ID.eq(objectId));
    }

    private static org.jooq.Condition autosaveOf(String objectType, long objectId, long userId) {
        return of(objectType, objectId).and(REVISION.AUTOSAVE.isTrue()).and(REVISION.AUTHOR_ID.eq(userId));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
