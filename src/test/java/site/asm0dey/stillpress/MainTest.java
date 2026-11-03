package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void onlyServeAndTheDefaultCommandBindHttp() {
        assertThat(Main.serves()).isTrue();
        assertThat(Main.serves("serve")).isTrue();
        assertThat(Main.serves("import", "--wxr", "x.xml")).isFalse();
        assertThat(Main.serves("publish")).isFalse();
        assertThat(Main.serves("verify-urls", "--source", "http://a", "--target", "http://b")).isFalse();
        assertThat(Main.serves("--help")).isFalse();
    }
}
