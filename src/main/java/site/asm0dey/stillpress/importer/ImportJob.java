package site.asm0dey.stillpress.importer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.SitePublisher;

/**
 * Runs an uploaded WXR import and the first publish on a background thread, tracking progress for the admin.
 */
@ApplicationScoped
public class ImportJob {
    private static final Logger LOG = Logger.getLogger(ImportJob.class);
    @Inject
    ImportGuard guard;
    @Inject
    WordPressImporter importer;
    @Inject
    SitePublisher publisher;
    @Inject
    DataDir dataDir;
    private volatile int items, media;
    private volatile String report, error;

    /**
     * @param state {@code idle}, {@code running}, {@code done} or {@code failed}; a failure with a {@code report}
     *        happened after the import wrote content
     */
    public record Status(String state, int items, int media, String report, String error) {}

    /**
     * Moves {@code wxr} into the data directory and starts the import; returns at once.
     */
    public void start(Path wxr, ImportOptions options) throws AlreadyImportedException {
        guard.begin();
        items = 0;
        media = 0;
        report = null;
        error = null;
        Path file;
        try {
            var dir = Files.createDirectories(dataDir.root().resolve("import"));
            file = Files.move(wxr, dir.resolve(System.currentTimeMillis() + ".xml"));
        } catch (IOException e) {
            fail(e);
            throw new UncheckedIOException(e);
        }
        // a plain thread: no request context or transaction leaks in; the importer opens its own
        Thread
            .ofVirtual()
            .name("wxr-import")
            .start(() -> run(file, options));
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
            report = importer.importWxr(in, Integer.MAX_VALUE, options, listener).toText();
            publisher.publishAll();
            guard.done();
        } catch (Exception e) {
            fail(e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warn("Could not delete the uploaded export " + file, e);
            }
        }
    }

    private void fail(Exception e) {
        LOG.error("WXR import failed", e);
        error = report == null
                ? Objects.requireNonNullElse(e.getMessage(), e.toString())
                : "Publishing failed after the import: " + Objects.requireNonNullElse(e.getMessage(), e.toString());
        guard.failed(error);
    }

    /**
     * The state is the persisted one, so an import run by the CLI or before a restart shows too.
     */
    public Status status() {
        var state = guard.state().orElse("idle");
        return new Status(state, items, media, report, error);
    }
}
