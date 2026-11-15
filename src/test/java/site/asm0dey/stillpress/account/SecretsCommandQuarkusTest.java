package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;
import site.asm0dey.stillpress.store.SecretRepository;

@QuarkusTest
class SecretsCommandQuarkusTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    Secrets secrets;
    @Inject
    SecretRepository repository;
    @Inject
    Captcha captcha;
    @Inject
    TwoFactorRepository factors;
    @Inject
    AuthorRepository users;
    @Inject
    site.asm0dey.stillpress.store.SettingRepository settings;
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
            .isEqualTo(
                    "Deleted 2 stored secrets and 0 authenticator apps. Re-enter the SMTP password and CAPTCHA "
                    + "secret in the admin; users with an authenticator app must add it again. Passkeys are kept."
            );
        assertThat(repository.ciphertext("smtp_password")).isEmpty();
        assertThat(repository.ciphertext("turnstile_secret")).isEmpty();
    }

    @Test
    void resetDropsTotpKeepsPasskeys() {
        var id = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).id();
        secrets.put("smtp_password", "one");
        factors.putTotp(id, secrets.encrypt("totp:" + id, "seed"));
        factors.addPasskey(
                id,
                "laptop",
                new RequiredPersistedData("admin", "cred", UUID.randomUUID(), new byte[] {1}, -7, 0)
        );
        var cmd = new CommandLine(SecretsCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        assertThat(cmd.execute("reset")).isZero();
        assertThat(out.toString()).startsWith("Deleted 1 stored secrets and 1 authenticator apps. ");
        assertThat(factors.totp(id)).isEmpty();
        assertThat(factors.passkeys(id)).hasSize(1);
    }

    @Test
    void resetSwitchesOffTurnstileWhoseSecretIsGone() {
        settings.put("captcha_provider", "turnstile");
        secrets.put("turnstile_secret", "two");
        var cmd = new CommandLine(SecretsCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        assertThat(cmd.execute("reset")).isZero();
        assertThat(captcha.provider()).isEqualTo("none");
        assertThat(out.toString()).contains("CAPTCHA was switched off because its secret was deleted");
    }
}
