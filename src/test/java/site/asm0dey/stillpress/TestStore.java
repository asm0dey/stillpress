package site.asm0dey.stillpress;

import static site.asm0dey.stillpress.db.Tables.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.importer.ImportReport;
import site.asm0dey.stillpress.importer.WordPressImporter;

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

    public void reset() {
        // self-references first, so whole-table deletes never trip an FK on any database
        db.update(COMMENT).setNull(COMMENT.PARENT_ID).execute();
        db.update(ITEM).setNull(ITEM.PARENT_ID).execute();
        db.update(TERM).setNull(TERM.PARENT_ID).execute();
        db.deleteFrom(ITEM_TERM).execute();
        db.deleteFrom(COMMENT).execute();
        db.deleteFrom(MEDIA).execute();
        db.deleteFrom(ITEM).execute();
        db.deleteFrom(TERM).execute();
        db.deleteFrom(AUTHOR).execute();
        db.deleteFrom(REUSABLE_BLOCK).execute();
        db.deleteFrom(REDIRECT).execute();
        db.deleteFrom(SETTING).execute();
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
}
