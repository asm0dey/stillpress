package site.asm0dey.stillpress.publish;

import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * What the publisher is doing, kept in the {@code publish_state} setting so the admin of every replica (and of a CLI
 * run) sees it. Each write commits on its own, whatever transaction the caller is in.
 */
@ApplicationScoped
public class PublishStatus {
    static final String KEY = "publish_state";
    private static final int EVERY = 200;
    @Inject
    SettingRepository settings;
    @Inject
    DSLContext db;
    @Inject
    ObjectMapper json;
    private int persistedDone;

    /**
     * @param state {@code idle}, {@code queued} (changes wait and nothing runs), {@code running} or {@code failed}
     * @param total the expected number of files; an estimate, so {@code done} may pass it
     */
    public record State(String state, int done, int total, Instant lastPublishedAt, String error) {}

    public State current() {
        var s = stored();
        return "idle".equals(s.state()) && db.fetchExists(PUBLISH_QUEUE)
                ? new State("queued", s.done(), s.total(), s.lastPublishedAt(), null)
                : s;
    }

    public synchronized void running(int total) {
        persistedDone = 0;
        save(new State("running", 0, total, stored().lastPublishedAt(), null));
    }

    /**
     * Persisted at most once per {@value #EVERY} files.
     */
    public synchronized void progress(int done) {
        if (done - persistedDone >= EVERY) {
            persistedDone = done;
            var s = stored();
            save(new State("running", done, s.total(), s.lastPublishedAt(), null));
        }
    }

    public synchronized void finished(int files) {
        save(new State("idle", files, files, Instant.now(), null));
    }

    public synchronized void failed(String message) {
        var s = stored();
        save(new State("failed", s.done(), s.total(), s.lastPublishedAt(), message));
    }

    private State stored() {
        return settings
            .get(KEY)
            .map(v -> {
                try {
                    return json.readValue(v, State.class);
                } catch (JsonProcessingException e) {
                    return null;
                }
            })
            .orElse(new State("idle", 0, 0, null, null));
    }

    private void save(State s) {
        try {
            var v = json.writeValueAsString(s);
            QuarkusTransaction
                .requiringNew()
                .run(() -> settings.put(KEY, v));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
