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
        fetcher.reset();
    }

    @Test
    void importsSampleBlog() throws Exception {
        var report = importer.importWxr(sample());
        // 5994,103,100,2,101,102,7
        assertThat(report.items()).isEqualTo(7);
        assertThat(report.comments()).isEqualTo(3);
        assertThat(report.reusableBlocks()).isEqualTo(1);
        assertThat(report.passwordProtected()).containsExactly("/2021/03/01/secret-post/");
        assertThat(report.skippedPostTypes()).containsExactly("nav_menu_item");
        assertThat(report.unknownBlocks()).isEmpty();
        assertThat(report.unknownShortcodes()).isEmpty();
        assertThat(items.findByPermalink(MONTH)).isPresent();
        assertThat(items.findByPermalink(MONTH).orElseThrow().getBody()).doesNotContain("wp-content/uploads");
        assertThat(redirects.find("?p=5994").orElseThrow().getToPath()).isEqualTo(MONTH);
        assertThat(redirects.find("?p=102")).as("password-protected target").isEmpty();
        assertThat(redirects.find("?p=101")).as("draft").isEmpty();
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
        assertThat(items.count()).isEqualTo(7);
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
            .containsExactlyInAnyOrder(5994L, 103L, 100L, 2L);
        assertThat(db.fetchValue(ITEM.PUBLISHED_LOCAL, ITEM.SOURCE_ID.eq(103L))).isEqualTo(LocalDateTime.of(
                2023,
                2,
                1,
                1,
                30
        ));
        assertThat(db.fetchValue(ITEM.PUBLISHED_AT, ITEM.SOURCE_ID.eq(103L))).isEqualTo(LocalDateTime.of(
                2023,
                1,
                31,
                22,
                30
        ));
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
    void mediaLimitStopsDownloadsAndKeepsSourceUrls() throws Exception {
        var report = importer.importWxr(sample(), 1);
        assertThat(report.media()).isEqualTo(1);
        assertThat(fetcher.fetched()).hasSize(1);
        assertThat(db.fetchCount(MEDIA)).isEqualTo(1);
        assertThat(report.skippedMedia())
            .isNotEmpty()
            .anyMatch(u -> u.endsWith("/2020/05/c.jpg"));
        assertThat(report.missingMedia()).doesNotContainAnyElementsOf(report.skippedMedia());
        assertThat(report.missingMedia()).noneMatch(u -> u.endsWith("/2020/05/c.jpg"));
        assertThat(items.findByPermalink("/2020/05/10/qa/").orElseThrow().getBody()).contains("/2020/05/c.jpg");
        assertThat(report.toText(1)).contains("Skipped media (limit 1)");
    }

    @Test
    void noLimitLeavesSkippedMediaEmpty() throws Exception {
        var report = importer.importWxr(sample());
        assertThat(report.skippedMedia()).isEmpty();
        assertThat(report.toText()).doesNotContain("Skipped media");
    }

    @Test
    void skippedMediaTextListsTwentyThenCount() {
        var urls = IntStream
            .range(0, 25)
            .mapToObj(n -> "https://x/wp-content/uploads/" + n + ".jpg")
            .toList();
        var text = new ImportReport(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), urls, List.of())
            .toText(7);
        assertThat(text)
            .contains("Skipped media (limit 7): 25")
            .contains("/19.jpg")
            .doesNotContain("/20.jpg")
            .contains("…and 5 more");
    }

    @Test
    void failureRollsEverythingBack() {
        fetcher.failOn("/2023/01/a.jpg");
        assertThatThrownBy(() -> importer.importWxr(sample())).hasMessageContaining("boom");
        for (var t : List.of(ITEM, TERM, ITEM_TERM, AUTHOR, REUSABLE_BLOCK, COMMENT, MEDIA, REDIRECT, SETTING)) {
            assertThat(db.fetchCount(t)).as(t.getName()).isZero();
        }
    }

    static final String HOME = "https://blog.example.ru";

    /**
     * Minimal WXR: one author, one percent-encoded category, one published post with the given content.
     */
    static InputStream wxr(String home, String siteUrl, String content) {
        var xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <rss version="2.0" xmlns:excerpt="http://wordpress.org/export/1.2/excerpt/"
                  xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/"
                  xmlns:wp="http://wordpress.org/export/1.2/">
                <channel>
                  <title>T</title><link>%1$s</link><description>D</description><language>ru-RU</language>
                  <wp:wxr_version>1.2</wp:wxr_version>
                  <wp:base_site_url>%2$s</wp:base_site_url><wp:base_blog_url>%1$s</wp:base_blog_url>
                  <wp:author><wp:author_id>5</wp:author_id><wp:author_login><![CDATA[Pavel.Doe]]></wp:author_login><wp:author_email><![CDATA[p@example.ru]]></wp:author_email><wp:author_display_name><![CDATA[Pavel]]></wp:author_display_name></wp:author>
                  <wp:category><wp:term_id>30</wp:term_id><wp:category_nicename><![CDATA[%%d0%%bf%%d1%%80%%d0%%b8]]></wp:category_nicename><wp:category_parent><![CDATA[]]></wp:category_parent><wp:cat_name><![CDATA[При]]></wp:cat_name></wp:category>
                  <item>
                    <title><![CDATA[P]]></title>
                    <link>%1$s/2024/01/01/%%d0%%bf%%d1%%80%%d0%%b8/</link>
                    <dc:creator><![CDATA[Pavel.Doe]]></dc:creator>
                    <guid isPermaLink="false">%1$s/?p=1</guid>
                    <content:encoded><![CDATA[%3$s]]></content:encoded>
                    <excerpt:encoded><![CDATA[]]></excerpt:encoded>
                    <wp:post_id>1</wp:post_id>
                    <wp:post_date_gmt><![CDATA[2024-01-01 00:00:00]]></wp:post_date_gmt>
                    <wp:post_modified_gmt><![CDATA[2024-01-01 00:00:00]]></wp:post_modified_gmt>
                    <wp:comment_status><![CDATA[open]]></wp:comment_status>
                    <wp:post_name><![CDATA[%%d0%%bf%%d1%%80%%d0%%b8]]></wp:post_name>
                    <wp:status><![CDATA[publish]]></wp:status>
                    <wp:post_parent>0</wp:post_parent>
                    <wp:menu_order>0</wp:menu_order>
                    <wp:post_type><![CDATA[post]]></wp:post_type>
                    <wp:post_password><![CDATA[]]></wp:post_password>
                    <category domain="category" nicename="%%d0%%bf%%d1%%80%%d0%%b8"><![CDATA[При]]></category>
                  </item>
                </channel>
                </rss>
                """
            .formatted(home, siteUrl, content);
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    String importedBody(ImportReport report) {
        assertThat(report.missingMedia()).as("missing").isEmpty();
        return db.fetchValue(ITEM.BODY, ITEM.SOURCE_ID.eq(1L));
    }

    @Test
    void httpLinkOnHttpsHomeSharesOneMediaRow() throws Exception {
        var body = importedBody(importer.importWxr(
                wxr(
                        HOME,
                        HOME,
                        "<img src=\"http://blog.example.ru/wp-content/uploads/2024/01/h.jpg\"> "
                        + "<img src=\"https://blog.example.ru/wp-content/uploads/2024/01/h-300x200.jpg\">"
                )
        ));
        assertThat(body).doesNotContain("wp-content/uploads").contains("/media/2024/01/");
        assertThat(fetcher.fetched()).containsExactly(HOME + "/wp-content/uploads/2024/01/h.jpg");
        assertThat(db.fetchCount(MEDIA)).isEqualTo(1);
    }

    @Test
    void protocolRelativeAndWwwLinksFetchedOverHomeScheme() throws Exception {
        var body = importedBody(importer.importWxr(
                wxr(
                        HOME,
                        HOME,
                        "<img src=\"//blog.example.ru/wp-content/uploads/2024/01/p.jpg\"> "
                        + "<img src=\"https://www.blog.example.ru/wp-content/uploads/2024/01/w.jpg\">"
                )
        ));
        assertThat(body).doesNotContain("wp-content/uploads");
        assertThat(fetcher.fetched())
            .containsExactly(HOME + "/wp-content/uploads/2024/01/p.jpg", HOME + "/wp-content/uploads/2024/01/w.jpg");
    }

    @Test
    void subdirectorySiteUrlMatched() throws Exception {
        var body = importedBody(importer.importWxr(
                wxr(
                        "https://example.ru",
                        "https://example.ru/wp",
                        "<img src=\"https://example.ru/wp/wp-content/uploads/2024/01/s.jpg\"> "
                        + "<img src=\"https://example.ru/wp-content/uploads/2024/01/t.jpg\">"
                )
        ));
        assertThat(body).doesNotContain("wp-content/uploads");
        assertThat(fetcher.fetched())
            .containsExactly(
                    "https://example.ru/wp/wp-content/uploads/2024/01/s.jpg",
                    "https://example.ru/wp-content/uploads/2024/01/t.jpg"
            );
    }

    @Test
    void leftoverUploadUrlsAreReportedMissing() throws Exception {
        var report =
                importer.importWxr(
                        wxr(HOME, HOME, "<img src=\"https://cdn.other.net/wp-content/uploads/2024/01/x.jpg\">")
        );
        assertThat(report.missingMedia()).containsExactly("https://cdn.other.net/wp-content/uploads/2024/01/x.jpg");
    }

    @Test
    void entityAfterUrlIsNotCaptured() throws Exception {
        var body = importedBody(importer.importWxr(
                wxr(
                        HOME,
                        HOME,
                        "<div data-x=\"{&quot;u&quot;:&quot;https://blog.example.ru/wp-content/uploads/2024/01/q.jpg&quot;}\"></div>"
                )
        ));
        assertThat(fetcher.fetched()).containsExactly(HOME + "/wp-content/uploads/2024/01/q.jpg");
        assertThat(body).contains("q.jpg&quot;}").doesNotContain("wp-content/uploads");
    }

    @Test
    void originalNamedLikeSizeVariantFallsBackToItself() throws Exception {
        fetcher.missing("/2024/01/shot.png");
        var body = importedBody(importer.importWxr(
                wxr(HOME, HOME, "<img src=\"https://blog.example.ru/wp-content/uploads/2024/01/shot-1920x1080.png\">")
        ));
        assertThat(body).doesNotContain("wp-content/uploads").contains("-shot-1920x1080.png");
    }

    @Test
    void slugsStoredDecodedAndAuthorSlugIsNicename() throws Exception {
        importer.importWxr(wxr(HOME, HOME, "x"));
        assertThat(db.fetchValue(ITEM.SLUG, ITEM.SOURCE_ID.eq(1L))).isEqualTo("при");
        assertThat(db.fetchValue(TERM.SLUG, TERM.SOURCE_ID.eq(30L))).isEqualTo("при");
        assertThat(terms.forItem(db.fetchValue(ITEM.ID, ITEM.SOURCE_ID.eq(1L)))).hasSize(1);
        assertThat(db.fetchCount(TERM)).isEqualTo(1);
        assertThat(db.fetchValue(AUTHOR.SLUG, AUTHOR.LOGIN.eq("Pavel.Doe"))).isEqualTo("pavel-doe");
    }

    @Test
    void unparseableLinksAreReportedAndTheImportCarriesOn() throws Exception {
        String xml;
        try (var in = sample()) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .replace(
                        "<link>https://blog.example.ru/about/</link>",
                        "<link>https://blog.example.ru/ab%zzout/</link>"
                )
                .replace(
                        "<link>https://blog.example.ru/2020/05/10/qa/</link>",
                        "<link>https://blog.example.ru/2020/05/10/q%2Fa/</link>"
                );
        }
        var report = importer.importWxr(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(report.items()).isEqualTo(7);
        assertThat(report.unparseableLinks())
            .containsExactly("100: https://blog.example.ru/2020/05/10/q%2Fa/", "2: https://blog.example.ru/ab%zzout/");
        assertThat(report.toText()).contains(
                "Unparseable links (2):\n  100: https://blog.example.ru/2020/05/10/q%2Fa/\n"
        );
        assertThat(db.fetchValue(ITEM.PERMALINK, ITEM.SOURCE_ID.eq(2L))).isNull();
        assertThat(db.fetchValue(ITEM.PERMALINK, ITEM.SOURCE_ID.eq(100L))).isNull();
        assertThat(items.findByPermalink(MONTH)).isPresent();
        assertThat(redirects.find("?page_id=2")).isEmpty();
        // a published item without a permalink must never be rendered over the home page
        assertThat(items.findPublishedSummaries())
            .extracting(ItemRepository.Summary::id)
            .doesNotContain(
                    db.fetchValue(ITEM.ID, ITEM.SOURCE_ID.eq(2L)),
                    db.fetchValue(ITEM.ID, ITEM.SOURCE_ID.eq(100L))
            );
    }

    static final String PHOTON = "https://i0.wp.com/blog.example.ru/wp-content/uploads/2021/05/";

    @Test
    void photonSrcSrcsetHrefAndBlockJsonAreLocalisedFromTheHomeOrigin() throws Exception {
        var body = importedBody(importer.importWxr(
                wxr(
                        HOME,
                        HOME,
                        "<!-- wp:image {\"url\":\""
                        + PHOTON
                        + "p.jpg?ssl=1\"} -->"
                        + "<a href=\"https://i1.wp.com/blog.example.ru/wp-content/uploads/2021/05/p.jpg?ssl=1\">"
                        + "<img src=\""
                        + PHOTON
                        + "p.jpg?resize=1024%2C768&amp;ssl=1\" srcset=\""
                        + "https://i2.wp.com/blog.example.ru/wp-content/uploads/2021/05/p-300x225.jpg?w=300&amp;ssl=1 300w, "
                        + "https://i3.wp.com/www.blog.example.ru/wp-content/uploads/2021/05/p.jpg?fit=1024%2C768&amp;quality=80 1024w"
                        + "\"></a><!-- /wp:image -->"
                        + "<img src=\"//i0.wp.com/blog.example.ru/wp-content/uploads/2021/05/p.jpg?quality=90\">"
                )
        ));
        assertThat(body).doesNotContain("wp.com", "wp-content", "ssl=", "resize=", "fit=", "quality=", "w=300");
        assertThat(fetcher.fetched()).containsExactly(HOME + "/wp-content/uploads/2021/05/p.jpg");
        assertThat(db.fetchCount(MEDIA)).isEqualTo(1);
        var local = db.fetchValue(MEDIA.PATH);
        assertThat(body)
            .contains("{\"url\":\"" + local + "\"}")
            .contains("href=\"" + local + "\"")
            .contains("src=\"" + local + "\"")
            .contains("srcset=\"" + local + " 300w, " + local + " 1024w\"");
    }

    @Test
    void photonAndDirectUrlsForOneFileShareOneRowAndOneFetch() throws Exception {
        var body = importedBody(importer.importWxr(
                wxr(
                        HOME,
                        HOME,
                        "<img src=\"" + HOME + "/wp-content/uploads/2021/05/d.jpg\"> <img src=\"" + PHOTON + "d.jpg?w=640\">"
                )
        ));
        assertThat(fetcher.fetched()).containsExactly(HOME + "/wp-content/uploads/2021/05/d.jpg");
        assertThat(db.fetchCount(MEDIA)).isEqualTo(1);
        assertThat(body).doesNotContain("wp.com", "w=640");
    }

    @Test
    void photonUrlForAnotherHostIsLeftAlone() throws Exception {
        var other = "https://i2.wp.com/other.example.com/wp-content/uploads/2021/05/o.jpg?ssl=1";
        importer.importWxr(wxr(HOME, HOME, "<img src=\"" + other + "\">"));
        assertThat(fetcher.fetched()).isEmpty();
        assertThat(db.fetchValue(ITEM.BODY, ITEM.SOURCE_ID.eq(1L))).contains(other);
    }

    @Test
    void photonUrlSkippedByMediaLimitIsSkippedNotMissing() throws Exception {
        var report =
                importer.importWxr(wxr(HOME, HOME, "<img src=\"" + PHOTON + "s.jpg?resize=10%2C10&amp;ssl=1\">"), 0);
        assertThat(report.skippedMedia()).containsExactly(HOME + "/wp-content/uploads/2021/05/s.jpg");
        assertThat(report.missingMedia()).isEmpty();
    }
}
