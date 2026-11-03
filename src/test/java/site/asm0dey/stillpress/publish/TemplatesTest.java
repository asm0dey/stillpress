package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.publish.views.CommentView;
import site.asm0dey.stillpress.publish.views.ItemView;
import site.asm0dey.stillpress.publish.views.SiteView;

@QuarkusTest
class TemplatesTest {
    @Inject
    Engine engine;

    private static SiteView site() {
        return new SiteView("Blog", "d", "ru-RU", "https://blog.example.ru");
    }

    private static ItemView item(String title, String html, List<CommentView> comments) {
        return new ItemView(
                1,
                title,
                "/a/",
                "https://blog.example.ru/a/",
                html,
                "",
                Instant.parse("2020-01-02T03:04:05Z"),
                "Ann",
                "/author/ann/",
                List.of(),
                List.of(),
                comments,
                "https://blog.example.ru/?p=1"
        );
    }

    private String render(String name, Object... kv) {
        var t = engine.getTemplate(name).instance();
        for (int i = 0; i < kv.length; i += 2) {
            t.data((String) kv[i], kv[i + 1]);
        }
        return t.render();
    }

    @Test
    void singleRendersTitleOnceEscapedAndLang() {
        var html = render("plain/single", "site", site(), "item", item("Q&A", "<p>x</p>", List.of()));
        assertThat(html)
            .contains("<html lang=\"ru\"")
            .contains("<h1>Q&amp;A</h1>")
            .contains("<p>x</p>")
            .doesNotContain("&amp;amp;")
            .contains("/assets/block-library.css")
            .contains("/assets/plain.css");
    }

    @Test
    void commentsThreaded() {
        var reply = new CommentView(2, "B", null, Instant.EPOCH, "<p>r</p>", List.of());
        var top = new CommentView(1, "A", null, Instant.EPOCH, "<p>t</p>", List.of(reply));
        var html = render("plain/single", "site", site(), "item", item("T", "<p>x</p>", List.of(top)));
        assertThat(Jsoup.parse(html).select("li.comment li.comment")).hasSize(1);
    }

    @Test
    void rssUsesVerbatimGuidAndSafeCdata() {
        var i = item("T & U", "<p>a ]]> b</p>", List.of()).withGuid("https://blog.example.ru/?p=5994");
        var xml = render(
                "feed/rss2.xml",
                "site",
                site(),
                "items",
                List.of(i),
                "selfUrl",
                "https://blog.example.ru/feed/",
                "lastBuildDate",
                "x"
        );
        assertThat(xml)
            .startsWith("<?xml")
            .contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994</guid>")
            .contains("<title>T &amp; U</title>")
            .contains("a ]]]]><![CDATA[> b")
            .contains("<pubDate>Thu, 2 Jan 2020 03:04:05 +0000</pubDate>");
    }

    @Test
    void commentsFeedRenders() {
        var c = new CommentView(7, "A", null, Instant.EPOCH, "<p>t</p>", List.of());
        var xml = render("feed/comments-rss2.xml", "site", site(), "item", item("T", "", List.of(c)), "selfUrl", "u");
        assertThat(xml).startsWith("<?xml").contains("#comment-7</guid>").contains("<![CDATA[<p>t</p>]]>");
    }

    @Test
    void archiveAnd404Render() {
        var a = new site.asm0dey.stillpress.publish.views.ArchiveView(
                "H",
                List.of(item("T", "", List.of())),
                1,
                2,
                null,
                "/page/2/"
        );
        assertThat(render("plain/archive", "site", site(), "archive", a)).contains("href=\"/page/2/\"").contains(
                "<h1>H</h1>"
        );
        assertThat(render("plain/404", "site", site())).contains("Not found");
    }
}
