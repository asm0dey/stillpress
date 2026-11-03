package site.asm0dey.stillpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlockParserTest {
  @Test void namespacelessNameIsCore() {
    var b = BlockParser.parse("<!-- wp:paragraph -->\n<p>Привет</p>\n<!-- /wp:paragraph -->");
    assertThat(b).singleElement().satisfies(x -> {
      assertThat(x.name()).isEqualTo("core/paragraph");
      assertThat(x.innerHtml()).isEqualTo("\n<p>Привет</p>\n");
    });
  }
  @Test void voidBlockWithAttributes() {
    var b = BlockParser.parse("<!-- wp:block {\"ref\":42} /-->").getFirst();
    assertThat(b.name()).isEqualTo("core/block");
    assertThat(b.attrs().get("ref").asLong()).isEqualTo(42);
    assertThat(b.innerContent()).isEmpty();
  }
  @Test void nestedGalleryKeepsPlaceholders() {
    var doc = "<!-- wp:gallery {\"linkTo\":\"none\"} --><figure class=\"wp-block-gallery\">"
      + "<!-- wp:image {\"id\":1} --><figure><img src=\"a.jpg\"/></figure><!-- /wp:image -->"
      + "<!-- wp:image {\"id\":2} --><figure><img src=\"b.jpg\"/></figure><!-- /wp:image -->"
      + "</figure><!-- /wp:gallery -->";
    var g = BlockParser.parse(doc).getFirst();
    assertThat(g.innerBlocks()).extracting(Block::name).containsExactly("core/image", "core/image");
    assertThat(g.innerContent()).containsExactly("<figure class=\"wp-block-gallery\">", null, null, "</figure>");
  }
  @Test void freeformBetweenBlocks() {
    var b = BlockParser.parse("<p>loose</p><!-- wp:separator /-->");
    assertThat(b).extracting(Block::name).containsExactly(null, "core/separator");
    assertThat(b.getFirst().innerHtml()).isEqualTo("<p>loose</p>");
  }
  @Test void namespacedThirdPartyBlock() {
    assertThat(BlockParser.parse("<!-- wp:jetpack/tiled-gallery {\"columnWidths\":[[50,50]]} --><div></div><!-- /wp:jetpack/tiled-gallery -->")
      .getFirst().name()).isEqualTo("jetpack/tiled-gallery");
  }
  @Test void unclosedBlockBecomesFreeformNotException() {
    assertThat(BlockParser.parse("<!-- wp:paragraph --><p>x</p>")).isNotEmpty();
  }
  @Test void trailingFreeformAndStrayCloserAndBadJson() {
    var b = BlockParser.parse("<!-- wp:html {bad} --><i/><!-- /wp:html -->tail<!-- /wp:x -->");
    assertThat(b).extracting(Block::name).containsExactly("core/html", null);
    assertThat(b.getFirst().attrs().isObject()).isTrue();
    assertThat(b.getFirst().attrs()).isEmpty();
    assertThat(b.get(1).innerHtml()).isEqualTo("tail<!-- /wp:x -->");
  }
}
