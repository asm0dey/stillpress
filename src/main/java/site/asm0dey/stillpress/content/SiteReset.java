package site.asm0dey.stillpress.content;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.account.TwoFactorPolicy;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.importer.ImportRunningException;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.ReleaseSwitcher;
import site.asm0dey.stillpress.publish.SitePublisher;
import site.asm0dey.stillpress.store.PublishLock;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.SiteWipe;

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
    private static final Set<String> KEPT =
            Set.of("title", "language", "home_url", "timezone", TwoFactorPolicy.KEY, LOCK_ROW, ImportGuard.STATE);
    private static final Logger LOG = Logger.getLogger(SiteReset.class);
    private final SiteWipe siteWipe;
    private final PublishLock lock;
    private final ImportGuard guard;
    private final SitePublisher publisher;
    private final DataDir dataDir;
    private final SettingRepository settings;
    private final SiteMail mail;

    SiteReset(
            SiteWipe siteWipe,
            PublishLock lock,
            ImportGuard guard,
            SitePublisher publisher,
            DataDir dataDir,
            SettingRepository settings,
            SiteMail mail
    ) {
        this.siteWipe = siteWipe;
        this.lock = lock;
        this.guard = guard;
        this.publisher = publisher;
        this.dataDir = dataDir;
        this.settings = settings;
        this.mail = mail;
    }

    /**
     * Keeps one administrator (password kept, {@code source_id} cleared so the next import merges by login), the
     * caller's session and the wizard's basics.
     *
     * @param keepSession the caller's raw session token, or {@code null} to end every session
     * @return false when the rows are reset but a file step failed (logged)
     * @throws ImportRunningException while an import runs; nothing changes
     */
    public boolean reset(long keepAdministratorId, String keepSession) {
        return run(false, () -> siteWipe.reset(
                keepAdministratorId,
                keepSession,
                KEPT,
                SETUP_DEFAULTS,
                Jobs.PUBLISH_SWEEP
        ));
    }

    /**
     * Everything goes, users and settings included, so the next {@code serve} prints a setup token.
     *
     * @return false when the rows are wiped but a file step failed (logged)
     */
    public boolean wipe() {
        return wipe(false);
    }

    /**
     * @param force the operator asserts no import runs and the server is stopped: a {@code running} state left by a
     *        crash is cleared instead of refusing
     */
    public boolean wipe(boolean force) {
        return run(force, () -> siteWipe.wipe(Set.of(LOCK_ROW, ImportGuard.STATE), Jobs.PUBLISH_SWEEP));
    }

    private enum Outcome {
        DONE,
        FILES_FAILED,
        IMPORT_RUNNING
    }

    private boolean run(boolean force, Runnable rows) {
        var outcome = lock.holding(() -> {
            var importRunning = QuarkusTransaction
                .requiringNew()
                .call(() -> {
                    // claims the import_state row, so an import cannot start while this runs; refuses a running one
                    if (force) {
                        settings.delete(ImportGuard.STATE);
                    }
                    try {
                        guard.close();
                    } catch (ImportRunningException e) {
                        return true;
                    }
                    rows.run();
                    // closed (not just done) until the files are gone, so no import lands in import/ or media/ first
                    settings.put(ImportGuard.STATE, "closed");
                    return false;
                });
            if (importRunning) {
                return Outcome.IMPORT_RUNNING;
            }
            // the SMTP settings are gone with the rest
            mail.reload();
            try {
                files();
                return Outcome.DONE;
            } catch (IOException | RuntimeException e) {
                LOG.error("Reset the database, but cleaning up the files failed", e);
                return Outcome.FILES_FAILED;
            } finally {
                // import opens again only now
                QuarkusTransaction
                    .requiringNew()
                    .run(() -> settings.delete(ImportGuard.STATE));
            }
        });
        // thrown out here: QuarkusTransaction and the lock would wrap it
        if (outcome == Outcome.IMPORT_RUNNING) {
            throw new ImportRunningException();
        }
        return outcome == Outcome.DONE;
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
