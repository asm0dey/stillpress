package site.asm0dey.stillpress.importer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.xml.stream.XMLStreamException;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.ItemRepository;

/**
 * Runs an uploaded WXR import on a background thread and queues the first publish, tracking progress for the admin.
 */
@ApplicationScoped
public class ImportJob {
    static final String NOT_RUNNING_HERE =
            "No import is running in this server. If it stopped mid-way, wipe the data directory and run setup again.";
    private static final Logger LOG = Logger.getLogger(ImportJob.class);
    @Inject
    ImportGuard guard;
    @Inject
    WordPressImporter importer;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;
    @Inject
    DataDir dataDir;
    @Inject
    ItemRepository store;
    @ConfigProperty(name = "stillpress.import.media-limit")
    Optional<Integer> mediaLimit;
    // true from a successful begin() until the outcome is persisted: "running" without it is a dead import (or the CLI)
    private volatile boolean live;
    private volatile int items, media;
    private volatile String report, error;

    /**
     * @param state {@code idle}, {@code running}, {@code done} or {@code failed}
     * @param contentLeft the store holds content, so a failed import cannot be retried
     */
    public record Status(String state, int items, int media, String report, String error, boolean contentLeft) {}

    /**
     * Moves {@code wxr} into the data directory and starts the import; returns at once. A refused start changes
     * nothing; synchronized with {@link #status} so no poll sees this import begun but not yet live.
     */
    public synchronized void start(Path wxr, ImportOptions options) throws AlreadyImportedException {
        mediaLimit.ifPresent(n -> {
            if (n < 0) {
                throw new IllegalStateException("stillpress.import.media-limit must not be negative, got " + n);
            }
        });
        guard.begin();
        live = true;
        items = 0;
        media = 0;
        report = null;
        error = null;
        try {
            var dir = Files.createDirectories(dataDir.root().resolve("import"));
            var file = Files.move(wxr, dir.resolve(System.currentTimeMillis() + ".xml"));
            // a plain thread: no request context or transaction leaks in; the importer opens its own
            Thread
                .ofVirtual()
                .name("wxr-import")
                .start(() -> run(file, options));
        } catch (IOException | RuntimeException e) {
            fail(e);
            live = false;
            throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
        }
    }

    private void run(Path file, ImportOptions options) {
        try (var in = new BufferedInputStream(Files.newInputStream(file))) {
            var listener = new ImportListener() {
                @Override
                public void item(int done) {
                    items = done;
                }

                @Override
                public void media(int done) {
                    media = done;
                }
            };
            report = importer
                .importWxr(in, mediaLimit.orElse(Integer.MAX_VALUE), options, listener)
                .toText(mediaLimit.orElse(null));
            guard.done();
            // the dashboard follows the publish; the import itself is done
            queue.all();
            jobs.requestPublish();
        } catch (Throwable e) {
            fail(e);
            if (e instanceof VirtualMachineError vm) {
                throw vm;
            }
        } finally {
            live = false;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warn("Could not delete the uploaded export " + file, e);
            }
        }
    }

    private void fail(Throwable e) {
        var message = Objects.requireNonNullElse(e.getMessage(), e.toString());
        if (report == null && malformed(e)) {
            // the uploaded file is the user's input, not a server fault
            LOG.warn("WXR import failed: " + message);
            error = message;
        } else {
            LOG.error("WXR import failed", e);
            error = message;
        }
        guard.failed(error);
    }

    private static boolean malformed(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof XMLStreamException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The state is the persisted one, so an import run by the CLI or before a restart shows too.
     */
    public synchronized Status status() {
        var state = guard.state().orElse("idle");
        var problem = state.equals("running") && !live ? NOT_RUNNING_HERE : error;
        return new Status(state, items, media, report, problem, !store.contentEmpty());
    }
}
