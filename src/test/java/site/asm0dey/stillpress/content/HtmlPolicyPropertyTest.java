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
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.publish.views.ArchiveView;
import site.asm0dey.stillpress.publish.views.EntryView;
import site.asm0dey.stillpress.publish.views.ItemView;
import site.asm0dey.stillpress.publish.views.SiteView;
import site.asm0dey.stillpress.publish.views.ViewFactory;

/**
 * Generated fragments that pass the lower-role checks ({@link HtmlCheck}) stay contained when published through the
 * real templates (R27): with their content node emptied the page is the template's own page, and the content node
 * holds nothing that runs script. The oracle ({@link #contained}) is plain jsoup, independent of {@link HtmlPolicy}.
 */
@QuarkusTest
class HtmlPolicyPropertyTest {
    static final int ITERATIONS = Integer.getInteger("htmlPolicy.iterations", 3000);
    private static final long BLOCK = 7;
    private static final SiteView SITE = new SiteView("S", "", "en", "");
    @Inject
    HtmlCheck check;
    @Inject
    ViewFactory views;
    @Inject
    Engine engine;
    private static final Generator<String> SYNTAX = Generator.sampledFrom(
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
            "\r",
            "\f",
            "<!--",
            "-->",
            "&lt;",
            "&amp;",
            "&#39;",
            "&quot;",
            "&#x3c;",
            "[",
            "]"
    );
    private static final Generator<String> NAMES = Generator.sampledFrom(
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
            "strong",
            "span",
            "iframe",
            "select",
            "option",
            "template",
            "math",
            "noscript",
            "style",
            "script",
            "xmp",
            "plaintext",
            "noembed",
            "noframes",
            "html",
            "body",
            "head"
    );
    private static final Generator<String> ATTRIBUTES = Generator.sampledFrom(
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
            " srcdoc=",
            " hidden"
    );
    private static final Generator<String> VALUES = Generator.sampledFrom(
            "javascript:alert(1)",
            " JaVaScRiPt:x",
            "java\tscript:x",
            "https://www.youtube.com/embed/x",
            "https://evil.example/",
            "x",
            "/p",
            "data:image/png;base64,AA",
            "display:none"
    );
    private static final Generator<String> BLOCKS = Generator.sampledFrom(
            "<!-- wp:x /-->",
            "<!-- wp:paragraph -->",
            "<!-- /wp:paragraph -->",
            "<!-- wp:block {\"ref\":" + BLOCK + "} /-->",
            "<!-- wp:embed {\"url\":\"https://a.b/'\"} /-->",
            "<!-- wp:x {\"content\":\"<b>\"} /-->"
    );
    private static final Generator<String> PIECES = Generator.sampledFrom(
            "[caption width='9' caption='",
            "[caption id=\"a\" width=\"300\"]",
            "[/caption]",
            "<img src=x alt='",
            "' onerror=alert(1)>",
            "<html style=\"display:none\">",
            "<body hidden>",
            "<xmp>",
            "<plaintext>"
    );
    // well-formed pieces, so enough generated fragments pass and the containment is actually exercised
    private static final Generator<String> BENIGN = Generator.sampledFrom(
            "text",
            "Привет",
            " ",
            "\n\n",
            "<p>",
            "</p>",
            "<p>a</p>",
            "<b>x</b>",
            "<em>y</em>",
            "<a href=\"/x\">l</a>",
            "<img src=\"/a.png\" alt=\"a\">",
            "<ul><li>i</li></ul>",
            "<br>",
            "1 &lt; 2"
    );
    private static final Generator<String> TOKEN = Generator.frequency(
            new LinkedHashMap<>(Map.of(SYNTAX, 5, NAMES, 4, ATTRIBUTES, 3, VALUES, 2, BLOCKS, 3, PIECES, 3, BENIGN, 8))
    );
    private static final Generator<String> FRAGMENT =
            Generator
        .listsOf(IntDistribution.uniform(0, 30), TOKEN)
        .map(t -> String.join("", t));
    // three fragments must pass at once in the reusable-block property, so its pieces are shorter
    private static final Generator<String> SHORT =
            Generator
        .listsOf(IntDistribution.uniform(0, 12), TOKEN)
        .map(t -> String.join("", t));
    private static final Function<Long, Optional<String>> NO_BLOCKS = id -> Optional.empty();
    /**
     * Generated values that met the property's premise; checked so a property cannot pass vacuously.
     */
    private final AtomicInteger passed = new AtomicInteger();

    @Test
    void passingBodiesStayInsideTheSingleItemPage() {
        var empty = single("");
        check(FRAGMENT, f -> {
            for (var format : List.of("classic", "blocks")) {
                if (check.body(f, format, NO_BLOCKS).isEmpty()) {
                    passed.incrementAndGet();
                    var published = views.html(f, format, NO_BLOCKS);
                    contained(single(published), empty, "div.entry", 1, format + " " + f + " -> " + published);
                }
            }
        });
    }

    @Test
    void passingExcerptsStayInsideTheirEntryOnAnArchivePage() {
        var empty = archive("", "");
        check(Generator.zipWith(FRAGMENT, FRAGMENT, List::of), ab -> {
            var a = ab.get(0);
            var b = ab.get(1);
            if (!HtmlPolicy.fragment(a).isEmpty() || !HtmlPolicy.fragment(b).isEmpty()) {
                return;
            }
            passed.incrementAndGet();
            var page = archive(views.html(a, "classic", NO_BLOCKS), views.html(b, "classic", NO_BLOCKS));
            contained(page, empty, "div.excerpt", 2, a + " | " + b);
        });
    }

    @Test
    void passingBlockInsidePassingPostStaysInsideTheSingleItemPage() {
        var empty = single("");
        // the post shows the block between two generated pieces
        var posts = Generator.zipWith(SHORT, SHORT, (a, b) -> a + "<!-- wp:block {\"ref\":" + BLOCK + "} /-->" + b);
        check(Generator.zipWith(posts, SHORT, List::of), px -> {
            var post = px.get(0);
            var block = px.get(1);
            if (!check.body(post, "blocks", NO_BLOCKS).isEmpty()
                    || !check.body(block, "blocks", NO_BLOCKS).isEmpty()) {
                return;
            }
            passed.incrementAndGet();
            Function<Long, Optional<String>> lookup = id -> id == BLOCK ? Optional.of(block) : Optional.empty();
            var published = views.html(post, "blocks", lookup);
            contained(single(published), empty, "div.entry", 1, "post " + post + " with block " + block);
        });
    }

    /**
     * The oracle: {@code count} content nodes, none holding anything that runs script, and with them emptied the
     * page equals {@code emptyPage}.
     */
    private static void contained(String page, String emptyPage, String content, int count, String example) {
        var doc = Jsoup.parse(page);
        var nodes = doc.select(content);
        assertThat(nodes).as("content nodes for %s", example).hasSize(count);
        for (var n : nodes) {
            assertThat(scripting(n)).as("%s -> %s", example, n.html()).isEmpty();
            n.empty();
        }
        assertThat(doc.outerHtml()).as(example).isEqualTo(Jsoup.parse(emptyPage).outerHtml());
    }

    // attributes a browser loads or navigates to, SVG animation targets included
    private static final Set<String> NAVIGATING = Set.of(
            "href",
            "src",
            "action",
            "formaction",
            "xlink:href",
            "data",
            "poster",
            "background",
            "values",
            "to",
            "from",
            "by"
    );

    private static List<String> scripting(Element root) {
        var found = new ArrayList<String>();
        for (var e : root.getAllElements()) {
            if (Set
                .of("script", "iframe", "object", "embed", "style", "base", "meta", "link", "form")
                .contains(e.normalName())) {
                found.add("<" + e.normalName() + ">");
            }
            for (var a : e.attributes()) {
                var name = a.getKey().toLowerCase(Locale.ROOT);
                var value = a.getValue().replaceAll("[\\s\\p{Cntrl}]", "").toLowerCase(Locale.ROOT);
                var script = value.startsWith("javascript:") || value.startsWith("vbscript:");
                if (name.startsWith("on") || name.equals("srcdoc") || script && NAVIGATING.contains(name)) {
                    found.add(name + "=" + a.getValue());
                }
            }
        }
        return found;
    }

    private String single(String contentHtml) {
        var item =
                new ItemView(1, "T", "/t/", "/t/", contentHtml, "", null, "", null, List.of(), List.of(), List.of(), "");
        return engine.getTemplate("plain/single").data("site", SITE).data("item", item).render();
    }

    private String archive(String a, String b) {
        var entries = Stream
            .of(a, b)
            .map(x -> engine.getTemplate("plain/entry").data("entry", new EntryView("T", "/t/", null, x)).render())
            .toList();
        return engine
            .getTemplate("plain/archive")
            .data("site", SITE)
            .data("archive", new ArchiveView("H", entries, 1, 1, null, null))
            .render();
    }

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
        assertThat(passed.get()).as("generated values meeting the premise").isGreaterThan(ITERATIONS / 10);
    }
}
