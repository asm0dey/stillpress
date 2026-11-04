package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class BunVersionPinTest {
    @Test
    void quinoaBunEqualsMise() throws IOException {
        var mise = Files.readString(Path.of("mise.toml"));
        var props = Files.readString(Path.of("src/main/resources/application.properties"));
        var miseBun = first("(?m)^bun\\s*=\\s*\"([^\"]+)\"", mise);
        var quinoaBun = first("(?m)^quarkus\\.quinoa\\.package-manager-install\\.bun-version=(\\S+)", props);
        assertThat(miseBun).isPresent();
        assertThat(quinoaBun).isEqualTo(miseBun);
    }

    private static Optional<String> first(String regex, String text) {
        return Pattern
            .compile(regex)
            .matcher(text)
            .results()
            .map(r -> r.group(1))
            .findFirst();
    }
}
