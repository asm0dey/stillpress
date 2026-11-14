package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.importer.ImportOptions;
import site.asm0dey.stillpress.importer.WordPressImporter;
import site.asm0dey.stillpress.store.FragmentRepository;

/**
 * Opt-in: imports the WXR named by STILLPRESS_BENCH_WXR and prints what a one-post publish costs against a full one.
 */
@QuarkusTest
@EnabledIfEnvironmentVariable(named = "STILLPRESS_BENCH_WXR", matches = ".+")
class PublishCostBench {
    @Inject
    SitePublisher publisher;
    @Inject
    PublishQueue queue;
    @Inject
    FragmentRepository fragments;
    @Inject
    TestStore store;
    @Inject
    WordPressImporter importer;
    @Inject
    DSLContext db;

    @Test
    void incrementalCostOnRealBlog() throws Exception {
        store.reset();
        try (var in = Files.newInputStream(Path.of(System.getenv("STILLPRESS_BENCH_WXR")))) {
            importer.importWxr(in, 0, new ImportOptions(30, "category", "tag", 50));
        }
        publisher.publishAll();
        var full = publisher.publishAll();
        System.out.printf("full: %d files, %d ms%n", full.files(), full.took().toMillis());
        var id = db
            .select(ITEM.ID)
            .from(ITEM)
            .where(ITEM.TYPE.eq("post").and(ITEM.STATUS.eq("publish")))
            .orderBy(ITEM.PUBLISHED_AT.desc())
            .limit(1)
            .fetchOne(ITEM.ID);
        for (int k = 1; k <= 3; k++) {
            var r = db.selectFrom(ITEM).where(ITEM.ID.eq(id)).fetchOne();
            r.setTitle("Bench " + k);
            r.store();
            fragments.delete(List.of(id));
            queue.item(id);
            var batch = queue.snapshot().orElseThrow();
            var inc = publisher.publish(batch);
            queue.done(batch);
            System.out.printf(
                    "incremental %d: %d files, %d ms (%d %% of full)%n",
                    k,
                    inc.files(),
                    inc.took().toMillis(),
                    100 * inc.took().toMillis() / Math.max(1, full.took().toMillis())
            );
            assertThat(inc.files()).isLessThan(full.files());
        }
    }
}
