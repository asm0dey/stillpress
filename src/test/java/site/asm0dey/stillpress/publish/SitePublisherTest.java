package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.COMMENT;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.TERM;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;

@QuarkusTest
class SitePublisherTest {
    @Inject
    SitePublisher publisher;
    @Inject
    TestStore store;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;

    @BeforeEach
    void importSample() throws Exception {
        store.reset();
        store.importSample();
    }

    @Test
    void writesItemsAtDecodedPermalinks() {
        var r = publisher.publishAll();
        assertThat(r.release().resolve("2023/01/14/месяц-ноябрь-год-2022/index.html")).exists();
        assertThat(r.release().resolve("about/index.html")).exists();
        assertThat(r.files()).isPositive();
    }

    @Test
    void draftsAndPasswordProtectedNotPublished() throws Exception {
        var r = publisher.publishAll();
        try (var walk = Files.walk(r.release())) {
            assertThat(walk.map(Path::toString)).noneMatch(p -> p.contains("secret-post")
                    || p.contains("draft-post"));
        }
    }

    @Test
    void archivesAndFeeds() {
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("category/travel/europe/index.html")).exists();
        assertThat(rel.resolve("tag/kids/index.html")).exists();
        assertThat(rel.resolve("2023/01/index.html")).exists();
        assertThat(rel.resolve("2023/01/14/index.html")).exists();
        assertThat(rel.resolve("2023/index.html")).exists();
        assertThat(rel.resolve("author/mama/index.html")).exists();
        assertThat(rel.resolve("feed/index.html"))
            .content()
            .contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994</guid>");
        assertThat(rel.resolve("comments/feed/index.html")).content().contains("<rss");
        assertThat(rel.resolve("category/travel/feed/index.html")).exists();
        assertThat(rel.resolve("2023/01/14/месяц-ноябрь-год-2022/feed/index.html")).content().contains("<rss");
    }

    @Test
    void commentAnchorsAndFeedGuidsUseTheWxrCommentId() {
        var rel = publisher.publishAll().release();
        var dbId = db.fetchValue(COMMENT.ID, COMMENT.SOURCE_ID.eq(902L));
        assertThat(dbId).isNotEqualTo(902L);
        assertThat(rel.resolve("2023/01/14/месяц-ноябрь-год-2022/index.html"))
            .content()
            .contains("id=\"comment-902\"")
            .doesNotContain("id=\"comment-" + dbId + "\"");
        assertThat(rel.resolve("2023/01/14/месяц-ноябрь-год-2022/feed/index.html"))
            .content()
            .contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994#comment-902</guid>");
        assertThat(rel.resolve("comments/feed/index.html"))
            .content()
            .contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994#comment-902</guid>");
    }

    @Test
    void staticFiles() {
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("robots.txt")).hasContent("User-agent: *\nDisallow: /admin/\n");
        assertThat(rel.resolve("404.html")).content().contains("Not found");
        assertThat(rel.resolve("assets/plain.css")).exists();
        assertThat(rel.resolve("assets/block-library.css")).exists();
        assertThat(rel.resolve("assets/plain.js")).exists();
        assertThat(rel.resolve("index.html")).exists();
    }

    @Test
    void paginatesHomeWithoutPageOne() {
        dataSetPerPage("1");
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("page/2/index.html")).exists();
        assertThat(rel.resolve("page/1/index.html")).doesNotExist();
        assertThat(rel.resolve("index.html")).content().contains("href=\"/page/2/\"");
        assertThat(rel.resolve("page/2/index.html")).content().contains("rel=\"prev\" href=\"/\"");
    }

    @Test
    void mediaIsLinkedIntoRelease() throws Exception {
        var f = dataDir.media().resolve("2023/01/a.jpg");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "img");
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("media/2023/01/a.jpg")).hasContent("img");
    }

    @Test
    void currentPointsAtNewRelease() throws Exception {
        var rel = publisher.publishAll().release();
        assertThat(dataDir.current().toRealPath()).isEqualTo(rel.toRealPath());
    }

    @Test
    void failureMidRenderKeepsPreviousReleaseLive() throws Exception {
        var good = publisher.publishAll().release();
        var before = releases();
        // a permalink escaping the output root makes rendering throw after other files were written
        db.update(ITEM).set(ITEM.PERMALINK, "/../../escape/").where(ITEM.SLUG.eq("about")).execute();
        assertThatThrownBy(publisher::publishAll).isInstanceOf(RuntimeException.class);
        assertThat(dataDir.current().toRealPath()).isEqualTo(good.toRealPath());
        assertThat(releases()).isEqualTo(before);
    }

    @Test
    void failureAfterEveryFileIsWrittenStillKeepsPreviousReleaseLive() throws Exception {
        var good = publisher.publishAll().release();
        var before = releases();
        var written = new java.util.concurrent.atomic.AtomicInteger();
        publisher.beforeActivate(release -> {
            try (var walk = Files.walk(release)) {
                written.set((int) walk.filter(Files::isRegularFile).count());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IllegalStateException("boom");
        });
        try {
            assertThatThrownBy(publisher::publishAll).hasMessage("boom");
        } finally {
            publisher.beforeActivate(r -> {});
        }
        assertThat(written.get()).isPositive();
        assertThat(dataDir.current().toRealPath()).isEqualTo(good.toRealPath());
        assertThat(releases()).isEqualTo(before);
    }

    @Test
    void emptyTermAndAuthorArchivesAndFeedsArePublished() {
        db.insertInto(TERM, TERM.TAXONOMY, TERM.NAME, TERM.SLUG).values("post_tag", "Empty", "empty").execute();
        db.insertInto(TERM, TERM.TAXONOMY, TERM.NAME, TERM.SLUG).values("category", "Void", "void").execute();
        db.insertInto(AUTHOR, AUTHOR.LOGIN, AUTHOR.SLUG).values("ghost", "ghost").execute();
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("tag/empty/index.html")).exists();
        assertThat(rel.resolve("tag/empty/feed/index.html")).content().contains("<rss");
        assertThat(rel.resolve("category/void/index.html")).exists();
        assertThat(rel.resolve("category/void/feed/index.html")).exists();
        assertThat(rel.resolve("author/ghost/index.html")).exists();
        assertThat(rel.resolve("author/ghost/feed/index.html")).exists();
    }

    @Test
    void nonNumericPostsPerPageFallsBackToTen() {
        dataSetPerPage("lots");
        assertThat(publisher.publishAll().release().resolve("index.html")).exists();
    }

    @Test
    void trailingSlashOnHomeUrlNeverDoublesSlashes() throws Exception {
        db.execute("update setting set value = 'https://blog.example.ru/' where key = 'home_url'");
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("feed/index.html"))
            .content()
            .contains("href=\"https://blog.example.ru/feed/\"")
            .doesNotContain("ru//");
        assertThat(rel.resolve("comments/feed/index.html")).content().doesNotContain("ru//");
    }

    @Test
    void undatedPostsStayOutOfFeedsAndDateArchives() throws Exception {
        db.update(ITEM).setNull(ITEM.PUBLISHED_AT).where(ITEM.SLUG.eq("qa")).execute();
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("feed/index.html")).content().doesNotContain("?p=100");
        assertThat(rel.resolve("index.html")).content().contains("Q&amp;A");
        assertThat(rel.resolve("2020/index.html")).doesNotExist();
    }

    @Test
    void onlyCategoriesAndTagsGetTermArchives() throws Exception {
        db.insertInto(TERM, TERM.TAXONOMY, TERM.NAME, TERM.SLUG).values("nav_menu", "Main", "main").execute();
        db.insertInto(TERM, TERM.TAXONOMY, TERM.NAME, TERM.SLUG).values("nav_menu", "Kids menu", "kids").execute();
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("tag/main")).doesNotExist();
        assertThat(rel.resolve("tag/kids/index.html")).content().contains("Месяц ноябрь");
        assertThat(rel.resolve("tag/kids/feed/index.html")).content().contains("?p=5994");
    }

    @Test
    void approvedReplyToHeldCommentStillAppearsInSiteCommentsFeed() {
        var item = db.select(ITEM.ID).from(ITEM).where(ITEM.SLUG.eq("qa")).fetchOne(ITEM.ID);
        var held = db
            .insertInto(COMMENT, COMMENT.ITEM_ID, COMMENT.CONTENT, COMMENT.CREATED_AT, COMMENT.STATUS)
            .values(item, "held", java.time.LocalDateTime.of(2999, 1, 1, 0, 0), "hold")
            .returningResult(COMMENT.ID)
            .fetchOne(COMMENT.ID);
        db
            .insertInto(
                    COMMENT,
                    COMMENT.ITEM_ID,
                    COMMENT.PARENT_ID,
                    COMMENT.AUTHOR_NAME,
                    COMMENT.CONTENT,
                    COMMENT.CREATED_AT,
                    COMMENT.STATUS
            )
            .values(item, held, "Orphan Olga", "reply", java.time.LocalDateTime.of(2999, 1, 2, 0, 0), "approved")
            .execute();
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("comments/feed/index.html")).content().contains("Orphan Olga");
    }

    private Set<Path> releases() throws IOException {
        try (var list = Files.list(dataDir.releases())) {
            return list.collect(Collectors.toSet());
        }
    }

    private void dataSetPerPage(String n) {
        db.execute("update setting set value = ? where key = 'posts_per_page'", n);
    }
}
