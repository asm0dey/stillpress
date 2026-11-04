package site.asm0dey.stillpress;

import static site.asm0dey.stillpress.db.Tables.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.importer.ImportReport;
import site.asm0dey.stillpress.importer.WordPressImporter;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishLock;

/**
 * Test helper: empties the store and imports the sample fixture.
 */
@ApplicationScoped
public class TestStore {
    @Inject
    DSLContext db;
    @Inject
    DataDir dataDir;
    @Inject
    WordPressImporter importer;
    @Inject
    PublishLock lock;

    /**
     * Runs under the publish lock, so a background publish finishes before its data goes; the sweep's own row stays.
     */
    public void reset() {
        lock.holding(() -> {
            clear();
            return null;
        });
    }

    private void clear() {
        // self-references first, so whole-table deletes never trip an FK on any database
        db.update(COMMENT).setNull(COMMENT.PARENT_ID).execute();
        db.update(ITEM).setNull(ITEM.PARENT_ID).execute();
        db.update(TERM).setNull(TERM.PARENT_ID).execute();
        db.deleteFrom(ITEM_TERM).execute();
        db.deleteFrom(ITEM_FRAGMENT).execute();
        db.deleteFrom(PUBLISHED_ITEM).execute();
        db.deleteFrom(PUBLISH_QUEUE).execute();
        db.deleteFrom(SCHEDULED_TASKS).where(SCHEDULED_TASKS.TASK_NAME.ne(Jobs.PUBLISH_SWEEP)).execute();
        db.deleteFrom(REVISION).execute();
        db.deleteFrom(COMMENT).execute();
        db.deleteFrom(MEDIA).execute();
        db.deleteFrom(ITEM).execute();
        db.deleteFrom(TERM).execute();
        db.deleteFrom(SESSION).execute();
        db.deleteFrom(USER_TOKEN).execute();
        db.deleteFrom(REUSABLE_BLOCK).execute();
        db.deleteFrom(AUTHOR).execute();
        db.deleteFrom(REDIRECT).execute();
        // the row every publish locks (created by V4) stays: reset holds the lock on it right now
        db.deleteFrom(SETTING).where(SETTING.KEY.ne("publish_lock")).execute();
        try (var walk = Files.walk(dataDir.media())) {
            walk
                .sorted(Comparator.reverseOrder())
                .filter(p -> !p.equals(dataDir.media()))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ImportReport importSample() throws Exception {
        try (var in = sample()) {
            return importer.importWxr(in);
        }
    }

    public static InputStream sample() {
        return Objects.requireNonNull(TestStore.class.getClassLoader().getResourceAsStream("wxr/sample-blog.xml"));
    }

    /**
     * Waits up to {@code seconds} for {@code done}; the caller asserts on the values afterwards.
     */
    public static void await(BooleanSupplier done, int seconds) {
        var deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
