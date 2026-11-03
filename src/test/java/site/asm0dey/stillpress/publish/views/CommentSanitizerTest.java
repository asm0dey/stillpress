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
}
