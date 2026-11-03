package site.asm0dey.stillpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassicContentTest {
  @Test void autopWrapsParagraphs() {
    assertThat(ClassicContent.toHtml("Line one\n\nLine two")).isEqualTo("<p>Line one</p>\n<p>Line two</p>\n");
  }
  @Test void captionShortcodeBecomesFigure() {
    assertThat(ClassicContent.toHtml("[caption id=\"attachment_7\" width=\"300\"]<img src=\"c.jpg\" /> Подпись[/caption]"))
      .contains("<figure").contains("<figcaption class=\"wp-caption-text\">Подпись</figcaption>");
  }
  @Test void unknownShortcodeKept() {
    assertThat(ClassicContent.toHtml("[gallery ids=\"1,2\"]")).contains("[gallery ids=\"1,2\"]");
  }
  @Test void preBlocksKeepNewlines() {
    assertThat(ClassicContent.toHtml("a\n\n<pre>x\n\ny</pre>")).contains("<pre>x\n\ny</pre>");
  }
}
