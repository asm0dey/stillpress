package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.SecretRepository;

@QuarkusTest
class SecretsCommandTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    Secrets secrets;
    @Inject
    SecretRepository repository;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void resetDeletesStoredSecrets() {
        secrets.put("smtp_password", "one");
        secrets.put("turnstile_secret", "two");
        var cmd = new CommandLine(SecretsCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        assertThat(cmd.execute("reset")).isZero();
        assertThat(out.toString().strip())
            .isEqualTo("Deleted 2 stored secrets. Re-enter the SMTP password and CAPTCHA secret in the admin.");
        assertThat(repository.ciphertext("smtp_password")).isEmpty();
        assertThat(repository.ciphertext("turnstile_secret")).isEmpty();
    }
}
