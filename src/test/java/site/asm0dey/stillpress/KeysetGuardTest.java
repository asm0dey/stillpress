package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.config.Keys;
import site.asm0dey.stillpress.store.SecretRepository;

@QuarkusTest
class KeysetGuardTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    Secrets secrets;
    @Inject
    SecretRepository repository;
    @TempDir
    Path data;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void refusesFreshKeysWithStoredSecrets() throws IOException {
        secrets.put("smtp_password", "one");
        var fresh = Keys.get(data, Optional.empty());
        assertThat(KeysetGuard.check(fresh, secrets, new PrintWriter(err, true))).isOne();
        assertThat(err.toString())
            .contains("stillpress secrets reset")
            .contains("Stillpress created a new encryption keyset in " + fresh.dir() + ",");
        try (var files = Files.list(fresh.dir())) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void passesFreshKeysWithoutSecrets() {
        var fresh = Keys.get(data, Optional.empty());
        assertThat(KeysetGuard.check(fresh, secrets, new PrintWriter(err, true))).isZero();
        assertThat(err.toString()).isEmpty();
        assertThat(fresh.dir().resolve("aead.json")).exists();
    }

    @Test
    void passesExistingKeysWithSecrets(@TempDir Path override) throws IOException {
        var generated = Keys.get(data, Optional.empty());
        for (var name : List.of("aead.json", "prf.json")) {
            Files.copy(generated.dir().resolve(name), override.resolve(name));
        }
        secrets.put("smtp_password", "one");
        var existing = Keys.get(data, Optional.of(override));
        assertThat(KeysetGuard.check(existing, secrets, new PrintWriter(err, true))).isZero();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void publishRefusesFreshKeysWithStoredSecrets() throws IOException {
        secrets.put("smtp_password", "one");
        var fresh = Keys.get(data, Optional.empty());
        assertThat(run(fresh, "publish")).isOne();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("stillpress secrets reset");
        try (var files = Files.list(fresh.dir())) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void secretsResetAndHelpRunOnFreshKeys() {
        secrets.put("smtp_password", "one");
        var fresh = Keys.get(data, Optional.empty());
        assertThat(run(fresh, "--help")).isZero();
        assertThat(out.toString()).contains("Usage: stillpress");
        assertThat(run(fresh, "secrets", "reset")).isZero();
        assertThat(repository.ciphertext("smtp_password")).isEmpty();
        assertThat(fresh.dir().resolve("aead.json")).exists();
    }

    private int run(Keys keys, String... args) {
        var cmd = new CommandLine(StillpressCommand.class, factory)
            .setExecutionStrategy(KeysetGuard.strategy(() -> keys, secrets));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }
}
