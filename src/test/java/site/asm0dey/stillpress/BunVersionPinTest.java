package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class BunVersionPinTest {
    @Test
    void quinoaBunEqualsMise() throws IOException {
        var mise = Files.readString(Path.of("mise.toml"));
        var props = Files.readString(Path.of("src/main/resources/application.properties"));
        var m = Pattern.compile("(?m)^bun\\s*=\\s*\"([^\"]+)\"").matcher(mise);
        var p = Pattern.compile("(?m)^quarkus\\.quinoa\\.package-manager-install\\.bun-version=(\\S+)").matcher(props);
        assertThat(m.find() && p.find()).isTrue();
        assertThat(p.group(1)).isEqualTo(m.group(1));
    }
}
