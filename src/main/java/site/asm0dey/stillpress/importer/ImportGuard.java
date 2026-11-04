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
    @Inject
    ItemRepository items;
    @Inject
    SettingRepository settings;

    /**
     * Synchronized for callers in this process; the persisted state and the emptiness check, written in one
     * transaction, cover a CLI import in a second process.
     */
    public synchronized void begin() throws AlreadyImportedException {
        var refusal = QuarkusTransaction.requiringNew().call(() -> {
            var state = settings.get(STATE);
            if (state.isPresent() && !state.get().equals("failed")) {
                return "an import is already " + (state.get().equals("running") ? "running" : "done");
            }
            if (!items.contentEmpty()) {
                return "the site already has content; import works only into an empty site";
            }
            settings.put(STATE, "running");
            return null;
        });
        if (refusal != null) {
            throw new AlreadyImportedException(refusal);
        }
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
