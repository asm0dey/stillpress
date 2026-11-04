package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.db.tables.records.RevisionRecord;
import site.asm0dey.stillpress.store.RevisionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * One revision per save, the newest {@code revisions_keep} (25) kept per object; one autosave per object and user,
 * replaced on every autosave and never counted as a revision. Runs in the caller's transaction.
 */
@ApplicationScoped
public class Revisions {
    private static final int KEEP = 25;
    @Inject
    RevisionRepository repo;
    @Inject
    SettingRepository settings;

    public void saved(
            String objectType,
            long objectId,
            String title,
            String body,
            String bodyFormat,
            String excerpt,
            long authorId
    ) {
        repo.insert(objectType, objectId, title, body, bodyFormat, excerpt, authorId, false);
        repo.prune(objectType, objectId, settings.get("revisions_keep").map(Integer::parseInt).orElse(KEEP));
    }

    public void autosave(String objectType, long objectId, long userId, String title, String body, String excerpt) {
        repo.deleteAutosave(objectType, objectId, userId);
        repo.insert(objectType, objectId, title, body, null, excerpt, userId, true);
    }

    public void deleteAutosave(String objectType, long objectId, long userId) {
        repo.deleteAutosave(objectType, objectId, userId);
    }

    public Optional<RevisionRecord> autosaveOf(String objectType, long objectId, long userId) {
        return repo.autosave(objectType, objectId, userId);
    }

    public List<RevisionRecord> list(String objectType, long objectId) {
        return repo.list(objectType, objectId);
    }

    public Optional<RevisionRecord> find(long id) {
        return repo.find(id);
    }
}
