package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import module java.base;
import org.junit.jupiter.api.Test;

class PermalinksTest {
    @Test
    void decodesAndReencodesLowercase() {
        var p = Permalinks.decodedPath("https://blog.example.ru/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86/");
        assertThat(p).isEqualTo("/2023/01/14/месяц/");
        assertThat(Permalinks.encode(p)).isEqualTo("/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86/");
    }

    @Test
    void toFileMapsToIndexHtmlInsideRoot() {
        var root = Path.of("/out");
        assertThat(Permalinks.toFile(root, "/about/")).isEqualTo(Path.of("/out/about/index.html"));
        assertThat(Permalinks.toFile(root, "/")).isEqualTo(Path.of("/out/index.html"));
        assertThatThrownBy(() -> Permalinks.toFile(root, "/../etc/")).isInstanceOf(IllegalArgumentException.class);
    }
}
