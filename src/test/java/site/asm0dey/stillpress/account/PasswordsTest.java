package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import module java.base;
import org.junit.jupiter.api.Test;

class PasswordsTest {
    @Test
    void hashIsArgon2idAndVerifies() {
        var h = Passwords.hash("correct horse battery");
        assertThat(h).startsWith("$argon2id$");
        assertThat(Passwords.verify("correct horse battery", h)).isTrue();
        assertThat(Passwords.verify("wrong horse battery", h)).isFalse();
        assertThat(Passwords.verify("anything", null)).isFalse();
    }

    @Test
    void nullPasswordOrMalformedHashNeverVerifies() {
        var h = Passwords.hash("correct horse battery");
        assertThat(Passwords.verify(null, h)).isFalse();
        assertThat(Passwords.verify("correct horse battery", "not-a-hash")).isFalse();
        assertThat(Passwords.verify("correct horse battery", "$argon2id$v=19$garbage")).isFalse();
        assertThat(Passwords.verify("correct horse battery", "")).isFalse();
        for (var bad :
                List.of(
                        "$argon2id$v=19$m=x,t=2,p=1$c2FsdHNhbHQ$aGFzaGhhc2g",
                        "$argon2id$v=19$m=19456,t=2,p=1$!!!$aGFzaGhhc2g",
                        "$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ",
                        "$argon2id$v=x$m=19456,t=2,p=1$c2FsdHNhbHQ$aGFzaGhhc2g",
                        "$argon2id$"
        )) {
            assertThat(Passwords.verify("correct horse battery", bad)).as(bad).isFalse();
        }
    }

    @Test
    void lengthBounds() {
        assertThatThrownBy(() -> Passwords.validate("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Passwords.validate("x".repeat(1025))).isInstanceOf(IllegalArgumentException.class);
        Passwords.validate("twelve chars");
        Passwords.validate("x".repeat(1024));
    }
}
