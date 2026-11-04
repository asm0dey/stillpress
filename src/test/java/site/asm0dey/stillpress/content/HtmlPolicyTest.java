package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
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

    @Test
    void bypassesFromReviewAreReported() {
        for (var payload :
                List.of(
                        "<p>x</p><body onload=alert(document.cookie)>",
                        "<html onmouseover=alert(1)>",
                        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
                        "<!-- wp:html --><noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript><!-- /wp:html -->",
                        "<img src=x alt=\"[caption width='9' caption='<img src=y onerror=alert(1)>']z[/caption]\">",
                        "<!-- wp:embed {\"url\":\"javascript:alert(document.cookie)\"} /-->",
                        "<svg><a><animate attributeName=\"href\" values=\"javascript:alert(1)\"/><text y=\"20\">click</text></a></svg>",
                        "<svg><a><set attributeName=\"href\" to=\"javascript:alert(1)\"/><text>x</text></a></svg>"
        )) {
            assertThat(HtmlPolicy.violations(payload)).as(payload).isNotEmpty();
        }
        assertThat(HtmlPolicy.violations("<p>x</p><body onload=alert(1)>")).containsExactly(
                "onload attribute on <body>"
        );
        assertThat(HtmlPolicy.violations("<p>a</p><meta http-equiv=refresh content=0>")).containsExactly(
                "<meta> element"
        );
    }
}
