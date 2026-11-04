package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SetupTokenTest {
    @Test
    void noTokenWhenHttpIsDisabled() {
        var t = new SetupToken();
        t.httpEnabled = false;
        // users is never consulted: CLI commands must not touch or print the token
        t.onStart(null);
        assertThat(t.current()).isEmpty();
        assertThat(t.matches(null)).isFalse();
    }

    @Test
    void matchesOnlyTheCurrentTokenUntilDiscarded() {
        var t = new SetupToken();
        t.regenerate();
        var raw = t.current().orElseThrow();
        assertThat(raw).hasSize(32);
        assertThat(t.matches(raw)).isTrue();
        assertThat(t.matches(raw + "x")).isFalse();
        t.discard();
        assertThat(t.matches(raw)).isFalse();
    }
}
