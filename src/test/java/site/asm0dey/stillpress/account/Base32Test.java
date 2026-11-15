package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class Base32Test {
    @Test
    void rfc4648Vectors() {
        assertThat(Base32.encode("".getBytes(UTF_8))).isEmpty();
        assertThat(Base32.encode("f".getBytes(UTF_8))).isEqualTo("MY");
        assertThat(Base32.encode("foobar".getBytes(UTF_8))).isEqualTo("MZXW6YTBOI");
    }
}
