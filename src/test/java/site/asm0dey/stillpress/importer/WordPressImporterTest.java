package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.TestStore.sample;
import static site.asm0dey.stillpress.db.Tables.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.*;

@QuarkusTest
class WordPressImporterTest {
    static final String MONTH = "/2023/01/14/месяц-ноябрь-год-2022/";
    @Inject
    WordPressImporter importer;
    @Inject
    TestStore store;
    @Inject
    FakeMediaFetcher fetcher;
    @Inject
    ItemRepository items;
    @Inject
    RedirectRepository redirects;
    @Inject
    SettingRepository settings;
    @Inject
    CommentRepository comments;
    @Inject
    TermRepository terms;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;

    @BeforeEach
    void clean() {
        store.reset();
    }

    @AfterEach
    void restoreFetcher() {
        fetcher.failOn(null);
    }

    @Test
    void importsSampleBlog() throws Exception {
        var report = importer.importWxr(sample());
        // 5994,100,2,101,102,7
        assertThat(report.items()).isEqualTo(6);
        assertThat(report.comments()).isEqualTo(3);
        assertThat(report.reusableBlocks()).isEqualTo(1);
        assertThat(report.passwordProtected()).containsExactly("/2021/03/01/secret-post/");
        assertThat(report.skippedPostTypes()).containsExactly("nav_menu_item");
        assertThat(report.unknownBlocks()).isEmpty();
        assertThat(report.unknownShortcodes()).isEmpty();
        assertThat(items.findByPermalink(MONTH)).isPresent();
        assertThat(items.findByPermalink(MONTH).orElseThrow().getBody()).doesNotContain("wp-content/uploads");
        assertThat(redirects.find("?p=5994").orElseThrow().getToPath()).isEqualTo(MONTH);
        assertThat(redirects.find("?page_id=2").orElseThrow().getToPath()).isEqualTo("/about/");
        assertThat(redirects.find("/2020/05/10/qa/c/").orElseThrow().getToPath()).isEqualTo("/2020/05/10/qa/");
        assertThat(redirects.find("?attachment_id=7").orElseThrow().getToPath()).isEqualTo("/2020/05/10/qa/");
        try (var files = Files.list(dataDir.media().resolve("2023/01"))) {
            assertThat(files).isNotEmpty();
        }
    }

    @Test
    void storesRowsFaithfully() throws Exception {
        importer.importWxr(sample());
        assertThat(items.count()).isEqualTo(6);
        assertThat(settings.get("home_url")).contains("https://blog.example.ru");
        assertThat(settings.get("title")).contains("Блог");
        assertThat(settings.get("posts_per_page")).contains("10");
        var month = items.findByPermalink(MONTH).orElseThrow();
        assertThat(month.getBodyFormat()).isEqualTo("blocks");
        assertThat(month.getGuid()).isEqualTo("https://blog.example.ru/?p=5994");
        assertThat(month.getBody()).contains("/media/2023/01/");
        assertThat(comments.approvedFor(month.getId())).hasSize(2);
        assertThat(comments.approvedFor(month.getId()).get(1).getParentId())
            .isEqualTo(comments.approvedFor(month.getId()).get(0).getId());
        assertThat(terms.forItem(month.getId()))
            .extracting(r -> r.getSlug())
            .containsExactlyInAnyOrder("travel", "europe", "kids");
        assertThat(db.fetchValue(TERM.PARENT_ID, TERM.SLUG.eq("europe")))
            .isEqualTo(db.fetchValue(TERM.ID, TERM.SLUG.eq("travel")));
        var qa = items.findByPermalink("/2020/05/10/qa/").orElseThrow();
        assertThat(qa.getBodyFormat()).isEqualTo("classic");
        assertThat(db.fetchValue(ITEM.PARENT_ID, ITEM.SOURCE_ID.eq(7L))).isEqualTo(qa.getId());
        assertThat(db.fetchValue(ITEM.PERMALINK, ITEM.SOURCE_ID.eq(101L))).isNull();
        assertThat(blocks.content(6001L)).hasValueSatisfying(c -> assertThat(c).contains("Подпишитесь!"));
        assertThat(db.fetchValue(COMMENT.STATUS, COMMENT.SOURCE_ID.eq(3L))).isEqualTo("spam");
        assertThat(items.findPublished())
            .extracting(r -> r.getSourceId())
            .containsExactlyInAnyOrder(5994L, 100L, 2L, 102L);
    }

    @Test
    void secondImportRefused() throws Exception {
        importer.importWxr(sample());
        assertThatThrownBy(() -> importer.importWxr(sample())).isInstanceOf(AlreadyImportedException.class);
    }

    @Test
    void missingMediaReportedNotFatal() throws Exception {
        var report = importer.importWxr(sample());
        assertThat(report.missingMedia()).anyMatch(u -> u.endsWith("/2020/05/c.jpg"));
        assertThat(items.findByPermalink("/2020/05/10/qa/").orElseThrow().getBody()).contains("/2020/05/c.jpg");
    }

    @Test
    void failureRollsEverythingBack() {
        fetcher.failOn("/2023/01/a.jpg");
        assertThatThrownBy(() -> importer.importWxr(sample())).hasMessageContaining("boom");
        for (var t : List.of(ITEM, TERM, ITEM_TERM, AUTHOR, REUSABLE_BLOCK, COMMENT, MEDIA, REDIRECT, SETTING)) {
            assertThat(db.fetchCount(t)).as(t.getName()).isZero();
        }
    }
}
