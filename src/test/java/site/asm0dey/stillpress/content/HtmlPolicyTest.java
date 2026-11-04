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
        assertThat(HtmlPolicy.violations("<p>x</p><body onload=alert(1)>"))
            .containsExactlyInAnyOrder("<body> element", "onload attribute on <body>");
        assertThat(HtmlPolicy.violations("<p>a</p><meta http-equiv=refresh content=0>")).containsExactly(
                "<meta> element"
        );
    }

    @Test
    void blockCommentsInsideHtmlAreRefused() {
        for (var payload :
                List.of(
                        "<img src=x o<!-- wp:x /-->nerror=alert(1)>",
                        "<img src=x alt='<!-- wp:separator {\"q\":\"'\"} /-->' onerror=alert(1)>",
                        "<textarea></text<!-- wp:x /-->area><img src=x onerror=alert(1)></textarea>",
                        "<p title='<!-- wp:embed {\"url\":\"https://a.b/'\"} /--> <img src=x onerror=alert(1)>'>x</p>"
        )) {
            assertThat(HtmlPolicy.structure(payload)).as(payload).containsExactly("block comment inside HTML");
        }
        assertThat(HtmlPolicy.structure(
                "<!-- wp:group --><div><!-- wp:paragraph --><p>a</p><!-- /wp:paragraph --></div><!-- /wp:group -->"
        ))
            .isEmpty();
    }

    @Test
    void scriptWordsInNonUrlBlockAttributesPass() {
        assertThat(HtmlPolicy.violations(
                "<!-- wp:group {\"metadata\":{\"name\":\"JavaScript: examples\"}} --><div></div><!-- /wp:group -->"
        ))
            .isEmpty();
        assertThat(HtmlPolicy.violations(
                "<!-- wp:navigation-link {\"label\":\"JavaScript: intro\",\"url\":\"/js\"} /-->"
        ))
            .isEmpty();
        assertThat(HtmlPolicy.violations("<!-- wp:x {\"a\":[{\"HREF\":\" javascript:x\"}]} /-->"))
            .containsExactly("javascript: URL in a block attribute");
    }

    @Test
    void unclosedMarkupAtTheEndIsRefused() {
        for (var payload :
                List.of(
                        "<img src=x alt='",
                        "<p>a</p><textarea>b",
                        "<p>a</p><!-- open",
                        "<svg><circle r=\"1\"></circle>",
                        "<p>a</p><style>p{}",
                        "<p>a</p><table><tr><td>x"
        )) {
            assertThat(HtmlPolicy.balanced(payload)).as(payload).containsExactly("unclosed markup at the end");
        }
        assertThat(HtmlPolicy.balanced("<!-- wp:paragraph --><p>a</p><!-- /wp:paragraph -->\n<p>b</p>")).isEmpty();
        assertThat(HtmlPolicy.balanced("<svg><circle r=\"1\"></circle></svg>")).isEmpty();
        assertThat(HtmlPolicy.balanced("Plain classic text\n\nwith paragraphs")).isEmpty();
        assertThat(HtmlPolicy.balanced("<b id=\"sp-sentinel\"></b><img alt='")).containsExactly(
                "unclosed markup at the end"
        );
        assertThat(HtmlPolicy.balanced(null)).isEmpty();
    }

    @Test
    void balanceIsJudgedOnThePublishedForm() {
        // wpautop leaves "<li<br />" open as a start tag
        assertThat(HtmlPolicy.fragment("<li <p><li ")).containsExactly("unclosed markup at the end");
        // wpautop puts "</p><pre" inside the alt value, leaving "</pre>" with nothing to close
        assertThat(HtmlPolicy.fragment("<img alt=\"<pre\" src=x> t </pre>")).containsExactly("stray </pre> end tag");
        // wpautop leaves the last paragraph open; the next block-level element closes it
        assertThat(HtmlPolicy.balanced("<p>one</p>\n<p>two")).isEmpty();
        assertThat(HtmlPolicy.fragment("First line\n\nSecond <em>line</em>")).isEmpty();
        assertThat(HtmlPolicy.balanced("<p><a href=\"/x\">open")).containsExactly("unclosed markup at the end");
    }

    @Test
    void counterexamplesFoundByPropertyTests() {
        // a stray end tag would close the archive entry's own <div>
        assertThat(HtmlPolicy.balanced("</div src=' onerror=alert(1)>' onerror=alert(1)>")).contains(
                "stray </div> end tag"
        );
        assertThat(HtmlPolicy.balanced("<p>a</p></p>a</br>")).isEmpty();
        // found by the round-5 hunt: </body> moves the rest of the post out of the page's body
        assertThat(HtmlPolicy.fragment("</body\f<html style=\"display:none\"><!-- /wp:paragraph -->"))
            .contains("stray </body> end tag");
        // a block ending in "<" next to one starting with a letter forms a tag
        for (var end : List.of("math onclick=<", "a</", "a<!", "a<!-")) {
            assertThat(HtmlPolicy.balanced(end)).as(end).contains("unclosed markup at the end");
        }
        assertThat(HtmlPolicy.balanced("1 < 2")).isEmpty();
        // a "<" right before a block reference glues onto the block's first letters
        assertThat(HtmlPolicy.structure("<<!-- wp:block {\"ref\":7} /-->&#x3c;")).containsExactly(
                "block comment inside HTML"
        );
        assertThat(HtmlPolicy.structure("<svg><!-- wp:block {\"ref\":7} /--></svg>"))
            .containsExactly("block comment inside HTML");
        assertThat(HtmlPolicy.structure("1 < <!-- wp:block {\"ref\":7} /-->")).isEmpty();
        // served block HTML expands no shortcodes: "</<" opens a bogus comment, so nothing here is live
        var served = "[caption id=\"a\" width=\"300\"]</<img src=x alt='' onerror=alert(1)>[/caption]";
        assertThat(HtmlPolicy.inPublished(served)).isEmpty();
        assertThat(HtmlPolicy.violations(served)).containsExactly("onerror attribute on <img>");
    }

    @Test
    void pageElementsWithAttributesAreRefused() {
        assertThat(HtmlPolicy.violations("<html style=\"display:none\">")).containsExactly("<html> element");
        assertThat(HtmlPolicy.violations("<body hidden>")).containsExactly("<body> element");
        assertThat(HtmlPolicy.violations("x<body style=\"background:url(https://evil.example/a.png)\">"))
            .containsExactly("<body> element");
        assertThat(HtmlPolicy.violations("<p>a</p>")).isEmpty();
    }

    @Test
    void misnestedFormattingIsAcceptedStrayBlockEndTagsAreNot() {
        for (var misnested : List.of("<strong><em>x</strong></em>", "<a href=\"/x\"><b>bold</a></b>", "<b><p>x</b></p>")) {
            assertThat(HtmlPolicy.balanced(misnested)).as(misnested).isEmpty();
        }
        for (var stray : List.of("a</div>", "a</li>", "a</td>")) {
            assertThat(HtmlPolicy.balanced(stray)).as(stray).isNotEmpty();
        }
        assertThat(HtmlPolicy.balanced("<div>a</div></div>")).contains("stray </div> end tag");
        assertThat(HtmlPolicy.balanced("a</body><!-- c -->")).contains("stray </body> end tag");
        assertThat(HtmlPolicy.balanced("a</html>b")).isNotEmpty();
        assertThat(HtmlPolicy.balanced("<table><tr><td>x")).containsExactly("unclosed markup at the end");
    }
}
