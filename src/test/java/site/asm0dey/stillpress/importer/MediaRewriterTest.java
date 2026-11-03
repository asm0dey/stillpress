package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class MediaRewriterTest {
    @Test
    void rewritesSrcSrcsetJsonAndSizeVariants() {
        var r =
                new MediaRewriter(Map.of("https://b.ru/wp-content/uploads/2023/01/a.jpg", "/media/2023/01/abc123-a.jpg")
        );
        var out = r.rewrite(
                "<!-- wp:image {\"url\":\"https://b.ru/wp-content/uploads/2023/01/a.jpg\"} --><img "
                + "src=\"https://b.ru/wp-content/uploads/2023/01/a-300x200.jpg\" srcset=\"https://b.ru/wp-"
                + "content/uploads/2023/01/a.jpg 1024w\"/>"
        );
        assertThat(out).doesNotContain("wp-content/uploads").contains("/media/2023/01/abc123-a.jpg");
    }
}
