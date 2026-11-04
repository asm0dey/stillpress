package site.asm0dey.stillpress.importer;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Import runs at most once, from the wizard or the CLI: only into an empty store, and only when no import is running
 * or has finished. A failed import that left nothing behind may be retried.
 */
@ApplicationScoped
public class ImportGuard {
    public static final String STATE = "import_state";
    private static final String CLOSED = "closed";
    @Inject
    ItemRepository items;
    @Inject
    SettingRepository settings;

    /**
     * Claims the import: the state moves to {@code running} by compare-and-set, so of two processes starting at once
     * only one wins; the emptiness check runs in the same transaction.
     */
    public synchronized void begin() throws AlreadyImportedException {
        var refusal = QuarkusTransaction.requiringNew().call(() -> {
            var state = settings.get(STATE).orElse(null);
            if (CLOSED.equals(state)) {
                return "this site was started without an import; import works only on a fresh install";
            }
            if (state != null && !state.equals("failed")) {
                return "an import is already " + (state.equals("running") ? "running" : "done");
            }
            if (!items.contentEmpty()) {
                return "the site already has content; import works only into an empty site";
            }
            return settings.compareAndSet(STATE, state, "running") ? null : "an import is already running";
        });
        if (refusal != null) {
            throw new AlreadyImportedException(refusal);
        }
    }

    /**
     * Settles the site's origin as "never imported", in the caller's transaction. A compare-and-set on the row, so it
     * holds against another process too; a no-op once closed or done.
     *
     * @throws ImportRunningException while an import runs
     */
    public void close() {
        var state = settings.get(STATE).orElse(null);
        if (CLOSED.equals(state) || "done".equals(state)) {
            return;
        }
        if (!"running".equals(state) && settings.compareAndSet(STATE, state, CLOSED)) {
            return;
        }
        // lost the compare-and-set, or saw running: whoever won decides
        if ("running".equals(settings.get(STATE).orElse(null))) {
            throw new ImportRunningException();
        }
    }

    /**
     * Whether an import could start now: nothing decided yet (or a failed try) and nothing to collide with.
     */
    public boolean importable() {
        var state = settings.get(STATE).orElse(null);
        return (state == null || state.equals("failed")) && items.contentEmpty();
    }

    public void done() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> settings.put(STATE, "done"));
    }

    /**
     * Retry stays possible only if the store is still empty; {@link #begin} checks that.
     *
     * @param message why; callers log it with the cause, the persisted state keeps only the outcome
     */
    public void failed(String message) {
        QuarkusTransaction
            .requiringNew()
            .run(() -> settings.put(STATE, "failed"));
    }

    public Optional<String> state() {
        return settings.get(STATE);
    }
}
