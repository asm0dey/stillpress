package site.asm0dey.stillpress.content;

import static site.asm0dey.stillpress.db.Tables.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.importer.ImportRunningException;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishLock;
import site.asm0dey.stillpress.publish.ReleaseSwitcher;
import site.asm0dey.stillpress.publish.SitePublisher;
import site.asm0dey.stillpress.store.SessionRepository;

/**
 * Wipes an experiment: back to the state right after the wizard's first step ({@link #reset}), or to a fresh install
 * ({@link #wipe}). Rows go in one transaction; the files follow once it has committed.
 */
@ApplicationScoped
public class SiteReset {
    /**
     * What setup writes besides the wizard's answers.
     */
    public static final Map<String, String> SETUP_DEFAULTS =
            Map.of("posts_per_page", "10", "posts_per_rss", "10", "category_base", "category", "tag_base", "tag");
    // the row every publish locks (created by V4); reset holds the lock on it while it runs
    private static final String LOCK_ROW = "publish_lock";
    private static final Set<String> KEPT = Set.of("title", "language", "home_url", "timezone", LOCK_ROW);
    private static final Logger LOG = Logger.getLogger(SiteReset.class);
    @Inject
    DSLContext db;
    @Inject
    PublishLock lock;
    @Inject
    ImportGuard guard;
    @Inject
    SitePublisher publisher;
    @Inject
    DataDir dataDir;

    /**
     * Keeps one administrator (password kept, {@code source_id} cleared so the next import merges by login), the
     * caller's session and the wizard's basics.
     *
     * @param keepSession the caller's raw session token, or {@code null} to end every session
     * @return false when the rows are reset but a file step failed (logged)
     * @throws ImportRunningException while an import runs; nothing changes
     */
    public boolean reset(long keepAdministratorId, String keepSession) {
        return run(() -> {
            clearContent();
            db
                .deleteFrom(SESSION)
                .where(keepSession == null ? DSL.noCondition() : SESSION.ID.ne(SessionRepository.hash(keepSession)))
                .execute();
            db.deleteFrom(AUTHOR).where(AUTHOR.ID.ne(keepAdministratorId)).execute();
            db.update(AUTHOR).setNull(AUTHOR.SOURCE_ID).execute();
            db.deleteFrom(SETTING).where(SETTING.KEY.notIn(KEPT)).execute();
            var insert = db.insertInto(SETTING, SETTING.KEY, SETTING.VALUE);
            for (var e : SETUP_DEFAULTS.entrySet()) {
                insert = insert.values(e.getKey(), e.getValue());
            }
            insert.execute();
        });
    }

    /**
     * Everything goes, users and settings included, so the next {@code serve} prints a setup token.
     *
     * @return false when the rows are wiped but a file step failed (logged)
     */
    public boolean wipe() {
        return run(() -> {
            clearContent();
            db.deleteFrom(SESSION).execute();
            db.deleteFrom(AUTHOR).execute();
            db.deleteFrom(SETTING).where(SETTING.KEY.ne(LOCK_ROW)).execute();
        });
    }

    private enum Outcome {
        DONE,
        FILES_FAILED,
        IMPORT_RUNNING
    }

    private boolean run(Runnable rows) {
        var outcome = lock.holding(() -> {
            var importRunning = QuarkusTransaction
                .requiringNew()
                .call(() -> {
                    // claims the import_state row, so an import cannot start while this runs; refuses a running one
                    try {
                        guard.close();
                    } catch (ImportRunningException e) {
                        return true;
                    }
                    rows.run();
                    return false;
                });
            if (importRunning) {
                return Outcome.IMPORT_RUNNING;
            }
            try {
                files();
                return Outcome.DONE;
            } catch (IOException | RuntimeException e) {
                LOG.error("Reset the database, but cleaning up the files failed", e);
                return Outcome.FILES_FAILED;
            }
        });
        // thrown out here: QuarkusTransaction and the lock would wrap it
        if (outcome == Outcome.IMPORT_RUNNING) {
            throw new ImportRunningException();
        }
        return outcome == Outcome.DONE;
    }

    /**
     * Content and everything hanging off it, in foreign-key order. Leaves authors, sessions and settings to the caller.
     */
    private void clearContent() {
        // self-references first, so whole-table deletes never trip an FK on any database
        db.update(COMMENT).setNull(COMMENT.PARENT_ID).execute();
        db.update(ITEM).setNull(ITEM.PARENT_ID).execute();
        db.update(TERM).setNull(TERM.PARENT_ID).execute();
        db.deleteFrom(ITEM_TERM).execute();
        db.deleteFrom(ITEM_FRAGMENT).execute();
        db.deleteFrom(PUBLISHED_ITEM).execute();
        db.deleteFrom(PUBLISH_QUEUE).execute();
        // the recurring sweep stays; a picked publish is waiting for the lock this holds, and finds an empty queue
        db
            .deleteFrom(SCHEDULED_TASKS)
            .where(SCHEDULED_TASKS.TASK_NAME.ne(Jobs.PUBLISH_SWEEP), SCHEDULED_TASKS.PICKED.isFalse())
            .execute();
        db.deleteFrom(REVISION).execute();
        db.deleteFrom(COMMENT).execute();
        db.deleteFrom(MEDIA).execute();
        db.deleteFrom(ITEM).execute();
        db.deleteFrom(TERM).execute();
        db.deleteFrom(USER_TOKEN).execute();
        db.deleteFrom(REUSABLE_BLOCK).execute();
        db.deleteFrom(REDIRECT).execute();
    }

    /**
     * The empty site goes live at once; then the old releases, uploads and import leftovers go.
     */
    private void files() throws IOException {
        emptyDir(dataDir.media());
        emptyDir(dataDir.root().resolve("import"));
        var live = publisher.publishAll().release().toRealPath();
        try (var list = Files.list(dataDir.releases())) {
            for (var r : list.filter(Files::isDirectory).toList()) {
                if (!r.toRealPath().equals(live)) {
                    ReleaseSwitcher.deleteTree(r);
                }
            }
        }
    }

    private static void emptyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var list = Files.list(dir)) {
            for (var p : list.toList()) {
                ReleaseSwitcher.deleteTree(p);
            }
        }
    }
}
