package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.publish.views.ArchiveView;
import site.asm0dey.stillpress.publish.views.EntryView;
import site.asm0dey.stillpress.publish.views.SiteView;
import site.asm0dey.stillpress.publish.views.ViewFactory;

/**
 * Generated fragments that pass the lower-role checks stay safe as published, side by side on an archive page, and
 * with a passing reusable block resolved inside a passing post (R27).
 */
@QuarkusTest
class HtmlPolicyPropertyTest {
    private static final int ITERATIONS = 3000;
    private static final long BLOCK = 7;
    @Inject
    HtmlCheck check;
    @Inject
    ViewFactory views;
    @Inject
    Engine engine;
    /**
     * HTML-ish tokens, weighted toward the ones that open, close or smuggle markup.
     */
    private static final Generator<String> TOKEN = Generator.frequency(
            new LinkedHashMap<>(Map.of(
                    Generator.sampledFrom(
                            "<",
                            "</",
                            ">",
                            "/>",
                            "=",
                            "'",
                            "\"",
                            " ",
                            "\n",
                            "\n\n",
                            "\t",
                            "<!--",
                            "-->",
                            "&lt;",
                            "&amp;",
                            "&#39;",
                            "&quot;",
                            "&#x3c;",
                            "[",
                            "]"
                    ),
                    6,
                    Generator.sampledFrom(
                            "img",
                            "a",
                            "svg",
                            "textarea",
                            "title",
                            "pre",
                            "table",
                            "tr",
                            "td",
                            "p",
                            "li",
                            "ul",
                            "div",
                            "b",
                            "em",
                            "span",
                            "iframe",
                            "select",
                            "option",
                            "template",
                            "math",
                            "noscript",
                            "style",
                            "script"
                    ),
                    4,
                    Generator.sampledFrom(
                            " src=",
                            " href=",
                            " alt=",
                            " title=",
                            " class=",
                            " onerror=",
                            " onclick=",
                            " style=",
                            " id=",
                            " xlink:href=",
                            " srcdoc="
                    ),
                    4,
                    Generator.sampledFrom(
                            "javascript:alert(1)",
                            " JaVaScRiPt:x",
                            "java\tscript:x",
                            "https://www.youtube.com/embed/x",
                            "https://evil.example/",
                            "x",
                            "/p",
                            "data:image/png;base64,AA"
                    ),
                    3,
                    Generator.sampledFrom(
                            "<!-- wp:x /-->",
                            "<!-- wp:paragraph -->",
                            "<!-- /wp:paragraph -->",
                            "<!-- wp:block {\"ref\":" + BLOCK + "} /-->",
                            "<!-- wp:embed {\"url\":\"https://a.b/'\"} /-->",
                            "<!-- wp:x {\"content\":\"<b>\"} /-->"
                    ),
                    3,
                    Generator.sampledFrom(
                            "[caption width='9' caption='",
                            "[caption id=\"a\" width=\"300\"]",
                            "[/caption]",
                            "<p>",
                            "</p>",
                            "<img src=x alt='",
                            "' onerror=alert(1)>",
                            "<b>x</b>",
                            "text",
                            "Привет"
                    ),
                    4
            ))
    );
    private static final Generator<String> FRAGMENT =
            Generator
        .listsOf(IntDistribution.uniform(0, 30), TOKEN)
        .map(t -> String.join("", t));
    private static final Function<Long, Optional<String>> NO_BLOCKS = id -> Optional.empty();

    @Test
    void passingFragmentsPublishWithoutViolations() {
        check(FRAGMENT, f -> {
            for (var format : List.of("classic", "blocks")) {
                if (check.body(f, format, NO_BLOCKS).isEmpty()) {
                    passed.incrementAndGet();
                    var published = views.html(f, format, NO_BLOCKS);
                    var found = HtmlPolicy.inPublished(published);
                    assertThat(found).as("%s %s -> %s", format, f, published).isEmpty();
                }
            }
        });
    }

    @Test
    void passingExcerptsStayInsideTheirEntryOnAnArchivePage() {
        var empty = page("", "");
        check(Generator.zipWith(FRAGMENT, FRAGMENT, List::of), ab -> {
            var a = ab.get(0);
            var b = ab.get(1);
            if (!HtmlPolicy.fragment(a).isEmpty() || !HtmlPolicy.fragment(b).isEmpty()) {
                return;
            }
            passed.incrementAndGet();
            var doc = Jsoup.parse(page(views.html(a, "classic", NO_BLOCKS), views.html(b, "classic", NO_BLOCKS)));
            var excerpts = doc.select("div.excerpt");
            assertThat(excerpts).as("excerpt divs for %s | %s", a, b).hasSize(2);
            for (var e : excerpts) {
                assertThat(HtmlPolicy.inPublished(e.html())).as("%s | %s", a, b).isEmpty();
                e.empty();
            }
            // with the excerpts emptied, the page is the template's own markup
            assertThat(doc.outerHtml()).as("%s | %s", a, b).isEqualTo(Jsoup.parse(empty).outerHtml());
        });
    }

    @Test
    void passingBlockInsidePassingPostHasNoViolations() {
        check(Generator.zipWith(FRAGMENT, FRAGMENT, List::of), px -> {
            var post = px.get(0);
            var block = px.get(1);
            if (!check.body(post, "blocks", NO_BLOCKS).isEmpty()
                    || !check.body(block, "blocks", NO_BLOCKS).isEmpty()) {
                return;
            }
            if (post.contains("\"ref\":" + BLOCK)) {
                passed.incrementAndGet();
            }
            Function<Long, Optional<String>> lookup = id -> id == BLOCK ? Optional.of(block) : Optional.empty();
            var published = views.html(post, "blocks", lookup);
            assertThat(HtmlPolicy.inPublished(published)).as("post %s with block %s -> %s", post, block, published).isEmpty();
        });
    }

    private String page(String a, String b) {
        var entries = Stream
            .of(a, b)
            .map(x -> engine.getTemplate("plain/entry").data("entry", new EntryView("T", "/t/", null, x)).render())
            .toList();
        return engine
            .getTemplate("plain/archive")
            .data("site", new SiteView("S", "", "en", ""))
            .data("archive", new ArchiveView("H", entries, 1, 1, null, null))
            .render();
    }

    /**
     * Generated values that met the property's premise; checked so a property cannot pass vacuously.
     */
    private final AtomicInteger passed = new AtomicInteger();

    private <T> void check(Generator<T> gen, Consumer<T> property) {
        passed.set(0);
        PropertyChecker
            .customized()
            .withIterationCount(ITERATIONS)
            .silent()
            .forAll(gen, v -> {
                property.accept(v);
                return true;
            });
        System.out.println("premise met " + passed.get() + " of " + ITERATIONS);
        assertThat(passed.get()).as("generated values meeting the premise").isGreaterThan(ITERATIONS / 20);
    }
}
