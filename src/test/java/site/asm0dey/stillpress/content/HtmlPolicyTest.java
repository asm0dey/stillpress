package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HtmlPolicyTest {
    @Test
    void plainBlocksPass() {
        assertThat(HtmlPolicy.violations("<!-- wp:paragraph --><p><a href=\"/x\">x</a></p><!-- /wp:paragraph -->")).isEmpty();
    }

    @Test
    void scriptAndHandlersAreReported() {
        assertThat(HtmlPolicy.violations("<p onclick=\"go()\">a</p><script>x()</script>"))
            .containsExactlyInAnyOrder("onclick attribute on <p>", "<script> element");
    }

    @Test
    void javascriptUrl() {
        assertThat(HtmlPolicy.violations("<a href=\" JavaScript:alert(1)\">x</a>")).containsExactly(
                "javascript: URL in href"
        );
        assertThat(HtmlPolicy.violations("<a HREF=\"java\tscript:alert(1)\">x</a>")).containsExactly(
                "javascript: URL in href"
        );
        assertThat(HtmlPolicy.violations("<a href=\"vbscript:x\">x</a>")).containsExactly("vbscript: URL in href");
    }

    @Test
    void youtubeIframeAllowedOtherNot() {
        assertThat(HtmlPolicy.violations("<iframe src=\"https://www.youtube.com/embed/x\"></iframe>")).isEmpty();
        assertThat(HtmlPolicy.violations("<iframe src=\"https://www.youtube-nocookie.com/embed/x\"></iframe>")).isEmpty();
        assertThat(HtmlPolicy.violations("<iframe src=\"https://player.vimeo.com/video/1\"></iframe>")).isEmpty();
        assertThat(HtmlPolicy.violations("<iframe src=\"https://evil.example/\"></iframe>"))
            .containsExactly("<iframe> from evil.example");
        assertThat(HtmlPolicy.violations("<iframe src=\"https://notyoutube.com/\"></iframe>"))
            .containsExactly("<iframe> from notyoutube.com");
        assertThat(HtmlPolicy.violations("<iframe srcdoc=\"&lt;b&gt;\"></iframe>"))
            .containsExactlyInAnyOrder("srcdoc attribute on <iframe>", "<iframe> from no host");
    }

    @Test
    void htmlInsideBlockAttributesIsChecked() {
        assertThat(HtmlPolicy.violations("<!-- wp:x {\"content\":\"<img src=x onerror=y>\"} /-->"))
            .containsExactly("onerror attribute on <img>");
        assertThat(HtmlPolicy.violations("<!-- wp:x {\"a\":{\"b\":[\"<script>y</script>\"]}} /-->"))
            .containsExactly("<script> element");
    }

    @Test
    void dataImageAllowedOnImgOnly() {
        assertThat(HtmlPolicy.violations("<img src=\"data:image/png;base64,AAAA\">")).isEmpty();
        assertThat(HtmlPolicy.violations("<a href=\"data:image/png;base64,AAAA\">x</a>")).containsExactly(
                "data: URL in href"
        );
        assertThat(HtmlPolicy.violations("<img src=\"data:text/html,x\">")).containsExactly("data: URL in src");
    }

    @Test
    void refusedElementsAndStyles() {
        assertThat(HtmlPolicy.violations("<style>a{}</style><form></form><base href=\"/\">"))
            .containsExactlyInAnyOrder("<style> element", "<form> element", "<base> element");
        assertThat(HtmlPolicy.violations("<p style=\"width: Expression(alert(1))\">a</p>"))
            .containsExactly("script in style attribute on <p>");
        assertThat(HtmlPolicy.violations("<p style=\"background:url( javascript:x)\">a</p>"))
            .containsExactly("script in style attribute on <p>");
        assertThat(HtmlPolicy.violations("<p style=\"color:red\">a</p>")).isEmpty();
        assertThat(HtmlPolicy.violations(null)).isEmpty();
    }
}
