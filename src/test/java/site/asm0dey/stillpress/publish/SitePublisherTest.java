package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.ITEM;
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

    private Set<Path> releases() throws IOException {
        try (var list = Files.list(dataDir.releases())) {
            return list.collect(Collectors.toSet());
        }
    }

    private void dataSetPerPage(String n) {
        db.execute("update setting set value = ? where key = 'posts_per_page'", n);
    }
}
