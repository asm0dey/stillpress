package site.asm0dey.stillpress.publish.views;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class EntryViewTest {
    private static ItemView item(String content, String excerpt) {
        return new ItemView(
                1,
                "T",
                "/t/",
                "http://x/t/",
                content,
                excerpt,
                null,
                "",
                null,
                List.of(),
                List.of(),
                List.of(),
                "g"
        );
    }

    private static String words(int n) {
        return IntStream
            .rangeClosed(1, n)
            .mapToObj(i -> "w" + i)
            .collect(Collectors.joining(" "));
    }

    @Test
    void storedExcerptWins() {
        assertThat(EntryView.of(item("<p>body</p>", "<p>mine</p>")).excerptHtml()).isEqualTo("<p>mine</p>");
    }

    @Test
    void longBodyIsCutAtFiftyFiveWordsAndMarked() {
        var out = EntryView.of(item("<p>" + words(60).replace(" w30 ", " <b>w30</b>\n ") + "</p>", "")).excerptHtml();
        assertThat(out).isEqualTo(words(55) + " […]");
    }

    @Test
    void shortBodyIsNotMarked() {
        assertThat(EntryView.of(item("<p>One  two\n<em>three</em></p>", "  ")).excerptHtml()).isEqualTo("One two three");
    }

    @Test
    void markupInTextIsEscaped() {
        assertThat(EntryView.of(item("<p>a &lt;b&gt; &amp; c</p>", "")).excerptHtml()).isEqualTo("a &lt;b&gt; &amp; c");
    }

    @Test
    void emptyBodyGivesEmptyExcerpt() {
        assertThat(EntryView.of(item("", "")).excerptHtml()).isEqualTo("");
    }

    @Test
    void cyrillicWordsCount() {
        var body = IntStream
            .rangeClosed(1, 56)
            .mapToObj(i -> "слово" + i)
            .collect(Collectors.joining(" "));
        var out = EntryView.of(item("<p>" + body + "</p>", "")).excerptHtml();
        assertThat(out).endsWith("слово55 […]").doesNotContain("слово56");
    }
}
