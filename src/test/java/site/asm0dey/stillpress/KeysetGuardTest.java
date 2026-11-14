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
import site.asm0dey.stillpress.config.DataDir;
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
    @Inject
    DataDir dataDir;
    @TempDir
    Path data;
    @TempDir
    Path override;
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
        assertThat(files(fresh.dir())).isEmpty();
    }

    @Test
    void refusesExistingKeysThatDoNotDecryptTheSecrets() throws IOException {
        secrets.put("smtp_password", "one");
        var other = copy(Keys.get(data, Optional.empty()).dir());
        assertThat(other.generatedNow()).isFalse();
        assertThat(KeysetGuard.check(other, secrets, new PrintWriter(err, true))).isOne();
        assertThat(err.toString())
            .contains("The keyset in " + override + " does not decrypt the secrets stored in the database.")
            .contains("stillpress secrets reset");
        // only a keyset this start generated is deleted
        assertThat(files(override)).containsExactlyInAnyOrder("aead.json", "prf.json");
    }

    @Test
    void passesFreshKeysWithoutSecrets() {
        var fresh = Keys.get(data, Optional.empty());
        assertThat(KeysetGuard.check(fresh, secrets, new PrintWriter(err, true))).isZero();
        assertThat(err.toString()).isEmpty();
        assertThat(fresh.dir().resolve("aead.json")).exists();
    }

    @Test
    void passesTheKeysTheSecretsWereStoredWith() throws IOException {
        secrets.put("smtp_password", "one");
        var same = copy(dataDir.root().resolve("keys"));
        assertThat(KeysetGuard.check(same, secrets, new PrintWriter(err, true))).isZero();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void publishRefusesFreshKeysWithStoredSecrets() throws IOException {
        secrets.put("smtp_password", "one");
        var fresh = Keys.get(data, Optional.empty());
        assertThat(run(fresh, "publish")).isOne();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("stillpress secrets reset");
        assertThat(files(fresh.dir())).isEmpty();
    }

    @Test
    void helpDeletesFreshKeysSoTheNextStartRefuses() throws IOException {
        secrets.put("smtp_password", "one");
        var fresh = Keys.get(data, Optional.empty());
        assertThat(run(fresh, "--help")).isZero();
        assertThat(out.toString()).contains("Usage: stillpress");
        assertThat(files(fresh.dir())).isEmpty();
        assertThat(KeysetGuard.check(Keys.get(data, Optional.empty()), secrets, new PrintWriter(err, true))).isOne();
    }

    @Test
    void unknownCommandDeletesFreshKeysSoTheNextStartRefuses() throws IOException {
        secrets.put("smtp_password", "one");
        var fresh = Keys.get(data, Optional.empty());
        assertThat(run(fresh, "pubish")).isEqualTo(2);
        assertThat(files(fresh.dir())).isEmpty();
        assertThat(KeysetGuard.check(Keys.get(data, Optional.empty()), secrets, new PrintWriter(err, true))).isOne();
    }

    @Test
    void freshKeysWithoutSecretsSurviveHelp() {
        var fresh = Keys.get(data, Optional.empty());
        assertThat(run(fresh, "--help")).isZero();
        assertThat(fresh.dir().resolve("aead.json")).exists();
    }

    @Test
    void secretsResetRunsWhenTheCheckWouldRefuse() throws IOException {
        secrets.put("smtp_password", "one");
        var other = copy(Keys.get(data, Optional.empty()).dir());
        assertThat(run(other, "secrets", "reset")).isZero();
        assertThat(repository.ciphertext("smtp_password")).isEmpty();
        assertThat(files(override)).containsExactlyInAnyOrder("aead.json", "prf.json");
    }

    private Keys copy(Path keysDir) throws IOException {
        for (var name : List.of("aead.json", "prf.json")) {
            Files.copy(keysDir.resolve(name), override.resolve(name));
        }
        return Keys.get(data, Optional.of(override));
    }

    private static List<String> files(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    private int run(Keys keys, String... args) {
        var cmd = KeysetGuard.guard(new CommandLine(StillpressCommand.class, factory), () -> keys, secrets);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }
}
