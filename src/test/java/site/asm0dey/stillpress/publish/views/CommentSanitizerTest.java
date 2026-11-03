package site.asm0dey.stillpress.publish.views;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CommentSanitizerTest {
    @Test
    void stripsScriptAndHandlersAndBadProtocols() {
        var out = CommentSanitizer.clean(
                "<p>hi<script>alert(1)</script><img src=x onerror=alert(2)>"
                + "<a href=\"javascript:alert(3)\">j</a> <a href=\"data:text/html,x\">d</a></p>"
        );
        assertThat(out)
            .doesNotContain("<script")
            .doesNotContain("onerror")
            .doesNotContain("<img")
            .doesNotContain("javascript:")
            .doesNotContain("data:");
    }

    @Test
    void keepsAllowedMarkupAndForcesRel() {
        var out = CommentSanitizer.clean(
                "<p>a <a href=\"https://x.org/\" title=\"t\" onclick=\"z\">l</a> <strong>b</strong> <del datetime=\"d\">x</del><br>"
                + "<a href=\"mailto:a@b.c\">m</a> <u>u</u></p>"
        );
        assertThat(out)
            .contains("<a href=\"https://x.org/\" title=\"t\" rel=\"nofollow ugc\">l</a>")
            .contains("<strong>b</strong>")
            .contains("<del datetime=\"d\">x</del>")
            .contains("href=\"mailto:a@b.c\"")
            .doesNotContain("onclick")
            .doesNotContain("<u>");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "JAVASCRIPT:alert(1)",
            "&#106;avascript:alert(1)",
            "java&#x09;script:alert(1)",
            " javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "vbscript:x"
    })
    void bypassHrefsLoseTheHref(String href) {
        assertThat(CommentSanitizer.clean("<a href=\"" + href + "\">x</a>")).doesNotContain("href");
    }

    @Test
    void safeUrlOnlyKeepsHttpAndHttps() {
        assertThat(CommentSanitizer.safeUrl("HTTPS://ok.example")).isEqualTo("HTTPS://ok.example");
        assertThat(CommentSanitizer.safeUrl(" http://ok.example ")).isEqualTo("http://ok.example");
        for (var bad :
                new String[] {
                "JAVASCRIPT:alert(1)",
                "&#106;avascript:alert(1)",
                "java\tscript:alert(1)",
                " javascript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD4=",
                "vbscript:x",
                "//evil.example",
                "ftp://x"
        }) {
            assertThat(CommentSanitizer.safeUrl(bad)).as(bad).isNull();
        }
    }
}
