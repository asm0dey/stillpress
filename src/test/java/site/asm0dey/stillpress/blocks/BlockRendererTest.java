package site.asm0dey.stillpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlockRendererTest {
  @Inject BlockRenderer renderer;

  private static RenderContext ctx() { return new RenderContext(id -> Optional.empty(), 0); }

  @Test void staticBlocksRenderSavedHtml() {
    var html = renderer.render("<!-- wp:gallery --><figure class=\"wp-block-gallery\"><!-- wp:image --><figure class=\"wp-block-image\"><img src=\"a.jpg\"/></figure><!-- /wp:image --></figure><!-- /wp:gallery -->", ctx());
    assertThat(html).isEqualTo("<figure class=\"wp-block-gallery\"><figure class=\"wp-block-image\"><img src=\"a.jpg\"/></figure></figure>");
  }
  @Test void reusableBlockResolved() {
    var ctx = new RenderContext(id -> id == 1 ? Optional.of("<!-- wp:paragraph --><p>Hi</p><!-- /wp:paragraph -->") : Optional.empty(), 0);
    assertThat(renderer.render("<!-- wp:block {\"ref\":1} /-->", ctx)).isEqualTo("<p>Hi</p>");
    assertThat(renderer.render("<!-- wp:block {\"ref\":2} /-->", ctx)).isEmpty();
  }
  @Test void reusableBlockResolvedRecursivelyWithGuard() {
    var ctx = new RenderContext(id -> id == 1 ? Optional.of("<!-- wp:block {\"ref\":1} /-->") : Optional.empty(), 0);
    assertThat(renderer.render("<!-- wp:block {\"ref\":1} /-->", ctx)).isEmpty();
  }
  @Test void youtubeEmbedBecomesFacade() {
    var html = renderer.render("<!-- wp:embed {\"url\":\"https://youtu.be/dQw4w9WgXcQ\",\"providerNameSlug\":\"youtube\"} --><figure class=\"wp-block-embed\"><div class=\"wp-block-embed__wrapper\">\nhttps://youtu.be/dQw4w9WgXcQ\n</div></figure><!-- /wp:embed -->", ctx());
    assertThat(html).contains("data-yt-id=\"dQw4w9WgXcQ\"").doesNotContain("<iframe");
  }
  @Test void youtubeFacadeMarkupIsExact() {
    var html = renderer.render("<!-- wp:embed {\"url\":\"https://youtu.be/dQw4w9WgXcQ\"} --><figure></figure><!-- /wp:embed -->", ctx());
    assertThat(html).isEqualTo("<figure class=\"wp-block-embed is-type-video is-provider-youtube wp-block-embed-youtube\"><div class=\"wp-block-embed__wrapper\"><button type=\"button\" class=\"sp-yt-facade\" data-yt-id=\"dQw4w9WgXcQ\" style=\"background-image:url(https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg)\" aria-label=\"Play video\"></button></div></figure>");
  }
  @Test void youtubeUrlForms() {
    for (var u : List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/watch?feature=x&v=dQw4w9WgXcQ",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgXcQ")) {
      assertThat(renderer.render("<!-- wp:embed {\"url\":\"" + u + "\"} --><figure></figure><!-- /wp:embed -->", ctx()))
        .as(u).contains("data-yt-id=\"dQw4w9WgXcQ\"");
    }
  }
  @Test void otherEmbedBecomesLink() {
    var html = renderer.render("<!-- wp:embed {\"url\":\"https://example.org/x?a=1&b=2\"} --><figure></figure><!-- /wp:embed -->", ctx());
    assertThat(html).isEqualTo("<figure class=\"wp-block-embed\"><a href=\"https://example.org/x?a=1&amp;b=2\">https://example.org/x?a=1&amp;b=2</a></figure>");
  }
}
