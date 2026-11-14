package site.asm0dey.stillpress.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.RegistryConfiguration;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeysTest {
    @TempDir
    Path data;

    @Test
    void generatesBothKeysetsOwnerOnly() throws IOException {
        var keys = Keys.load(data, Optional.empty());
        assertThat(keys.generatedNow()).isTrue();
        assertThat(keys.dir()).isEqualTo(data.resolve("keys"));
        for (var name : List.of("aead.json", "prf.json")) {
            assertThat(Files.getPosixFilePermissions(keys.dir().resolve(name))).containsExactlyInAnyOrder(
                    OWNER_READ,
                    OWNER_WRITE
            );
        }
        assertThat(Files.getPosixFilePermissions(keys.dir())).containsExactlyInAnyOrder(
                OWNER_READ,
                OWNER_WRITE,
                OWNER_EXECUTE
        );
        try (var files = Files.list(keys.dir())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("aead.json", "prf.json");
        }
    }

    @Test
    void secondLoadReadsTheSameKeys() throws GeneralSecurityException {
        var first = Keys.load(data, Optional.empty());
        var second = Keys.load(data, Optional.empty());
        assertThat(second.generatedNow()).isFalse();
        var ad = "n".getBytes(UTF_8);
        var a = aead(first);
        var b = aead(second);
        assertThat(new String(b.decrypt(a.encrypt("one".getBytes(UTF_8), ad), ad), UTF_8)).isEqualTo("one");
        assertThat(new String(a.decrypt(b.encrypt("two".getBytes(UTF_8), ad), ad), UTF_8)).isEqualTo("two");
    }

    @Test
    void overrideDirWithoutKeysFails(@TempDir Path override) throws IOException {
        assertThatThrownBy(() -> Keys.load(data, Optional.of(override)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(override.toString());
        try (var files = Files.list(override)) {
            assertThat(files).isEmpty();
        }
        assertThat(data.resolve("keys")).doesNotExist();
    }

    @Test
    void overrideDirIsNeverWritten(@TempDir Path override) throws IOException {
        var generated = Keys.load(data, Optional.empty());
        for (var name : List.of("aead.json", "prf.json")) {
            Files.copy(generated.dir().resolve(name), override.resolve(name));
        }
        Files.setPosixFilePermissions(override, EnumSet.of(OWNER_READ, OWNER_EXECUTE));
        try {
            var keys = Keys.load(data.resolve("other"), Optional.of(override));
            assertThat(keys.generatedNow()).isFalse();
            assertThat(keys.dir()).isEqualTo(override);
        } finally {
            Files.setPosixFilePermissions(override, EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        }
    }

    private static Aead aead(Keys keys) throws GeneralSecurityException {
        return keys.aead().getPrimitive(RegistryConfiguration.get(), Aead.class);
    }
}
