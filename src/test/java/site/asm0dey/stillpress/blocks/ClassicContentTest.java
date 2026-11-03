package site.asm0dey.stillpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassicContentTest {
  @Test void autopWrapsParagraphs() {
    assertThat(ClassicContent.toHtml("Line one\n\nLine two")).isEqualTo("<p>Line one</p>\n<p>Line two</p>\n");
  }
  @Test void captionShortcodeBecomesFigure() {
    assertThat(ClassicContent.toHtml("[caption id=\"attachment_7\" width=\"300\"]<img src=\"c.jpg\" /> Подпись[/caption]"))
      .contains("<figure").contains("<figcaption id=\"caption-attachment-7\" class=\"wp-caption-text\">Подпись</figcaption>");
  }
  @Test void captionMatchesWpMarkupAndIsNotParagraphWrapped() {
    assertThat(ClassicContent.toHtml("[caption id=\"attachment_7\" width=\"300\"]<img src=\"c.jpg\" /> Подпись[/caption]"))
      .isEqualTo("<figure id=\"attachment_7\" aria-describedby=\"caption-attachment-7\" style=\"width: 300px\" class=\"wp-caption alignnone\"><img src=\"c.jpg\" /><figcaption id=\"caption-attachment-7\" class=\"wp-caption-text\">Подпись</figcaption></figure>\n");
  }
  @Test void unknownShortcodeKept() {
    assertThat(ClassicContent.toHtml("[gallery ids=\"1,2\"]")).contains("[gallery ids=\"1,2\"]");
  }
  @Test void preBlocksKeepNewlines() {
    assertThat(ClassicContent.toHtml("a\n\n<pre>x\n\ny</pre>")).contains("<pre>x\n\ny</pre>");
  }
}
