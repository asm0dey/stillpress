package site.asm0dey.stillpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import module java.base;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

class ClassicContentTest {
    @Test
    void autopWrapsParagraphs() {
        assertThat(ClassicContent.toHtml("Line one\n\nLine two")).isEqualTo("<p>Line one</p>\n<p>Line two</p>\n");
    }

    @Test
    void captionShortcodeBecomesFigure() {
        assertThat(ClassicContent.toHtml(
                "[caption id=\"attachment_7\" width=\"300\"]<img src=\"c.jpg\" /> Подпись[/caption]"
        ))
            .contains("<figure")
            .contains("<figcaption id=\"caption-attachment-7\" class=\"wp-caption-text\">Подпись</figcaption>");
    }

    @Test
    void captionMatchesWpMarkupAndIsNotParagraphWrapped() {
        assertThat(ClassicContent.toHtml(
                "[caption id=\"attachment_7\" width=\"300\"]<img src=\"c.jpg\" /> Подпись[/caption]"
        ))
            .isEqualTo(
                    "<figure id=\"attachment_7\" aria-describedby=\"caption-attachment-7\" style=\"width: "
                    + "300px\" class=\"wp-caption alignnone\"><img src=\"c.jpg\" /><figcaption id=\"caption-"
                    + "attachment-7\" class=\"wp-caption-text\">Подпись</figcaption></figure>\n"
            );
    }

    @Test
    void unautopStopsAtFirstClosingCaption() {
        var cap = "[caption id=\"a\" width=\"10\"]<img src=\"x\" /> %s[/caption]";
        var out = ClassicContent.toHtml(cap.formatted("A") + " tail\n\n" + cap.formatted("B"));
        assertThat(out)
            .startsWith("<p><figure")
            .contains("A</figcaption></figure> tail</p>\n<figure")
            .endsWith("</figure>\n");
    }

    @Test
    void captionKeepsLinkedImageAndRichText() {
        assertThat(ClassicContent.toHtml(
                "[caption id=\"attachment_7\" width=\"300\"]<a href=\"big.jpg\"><img src=\"c.jpg\" /></a> "
                + "Photo by <a href=\"x\">Bob</a>[/caption]"
        ))
            .contains(
                    "<a href=\"big.jpg\"><img src=\"c.jpg\" /></a><figcaption id=\"caption-attachment-7\" "
                    + "class=\"wp-caption-text\">Photo by <a href=\"x\">Bob</a></figcaption>"
            );
    }

    @Test
    void captionAttributeWinsAndContentIsNotSplit() {
        assertThat(ClassicContent.toHtml(
                "[caption width=\"50\" caption=\"Given\"]<img src=\"c.jpg\" /> ignored[/caption]"
        ))
            .contains("<img src=\"c.jpg\" /> ignored<figcaption class=\"wp-caption-text\">Given</figcaption>");
    }

    @Test
    void unknownShortcodeKept() {
        assertThat(ClassicContent.toHtml("[gallery ids=\"1,2\"]")).contains("[gallery ids=\"1,2\"]");
    }

    @Test
    void preBlocksKeepNewlines() {
        assertThat(ClassicContent.toHtml("a\n\n<pre>x\n\ny</pre>")).contains("<pre>x\n\ny</pre>");
    }

    @Test
    void captionWithManySlashesDoesNotOverflowTheStack() {
        assertThat(ClassicContent.toHtml("[caption x" + "/a".repeat(200_000) + "]")).contains("[caption x/a/a");
    }

    @Test
    // the slow regex lineBreaks replaces, kept as the oracle
    @SuppressWarnings("java:S8786")
    void lineBreaksMatchesWordPressRegex() {
        var text = Generator
            .listsOf(
                    IntDistribution.uniform(0, 20),
                    Generator.sampledFrom(" ", "\t", "\n", "\r", "a", "<br />", "<br /", ">")
            )
            .map(t -> String.join("", t));
        PropertyChecker
            .customized()
            .withIterationCount(5_000)
            .silent()
            .forAll(text, s -> ClassicContent.lineBreaks(s).equals(s.replaceAll("(?<!<br />)\\s*\n", "<br />\n")));
    }

    @Test
    void lineBreaksIsLinearOnLongWhitespace() {
        var s = "a" + " ".repeat(1_000_000) + "b\n";
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> ClassicContent.lineBreaks(s));
    }

    @Test
    void attrEscapesSingleQuote() {
        assertThat(ClassicContent.attr("a'b\"<>&")).isEqualTo("a&#039;b&quot;&lt;&gt;&amp;");
    }
}
