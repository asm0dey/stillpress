package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;
import site.asm0dey.stillpress.store.SecretRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@ExtendWith(MockitoExtension.class)
class SecretsCommandTest {
    @Mock
    SecretRepository secrets;
    @Mock
    Captcha captcha;
    @Mock
    TwoFactorRepository factors;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(CommandLine.Model.CommandSpec.create());
        cmd.addSubcommand("reset", new SecretsCommand.Reset(secrets, captcha, factors));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
    }

    @Test
    void resetReportsWhatItDeleted() {
        when(secrets.deleteAll()).thenReturn(2);
        when(factors.deleteAllTotp()).thenReturn(1);
        when(captcha.provider()).thenReturn("none");
        assertThat(cmd.execute("reset")).isZero();
        assertThat(out.toString().strip())
            .isEqualTo(
                    "Deleted 2 stored secrets and 1 authenticator apps. Re-enter the SMTP password and CAPTCHA "
                    + "secret in the admin; users with an authenticator app must add it again. Passkeys are kept."
            );
        assertThat(err.toString()).isEmpty();
        verify(captcha, never()).turnOff();
    }

    @Test
    void resetSwitchesOffTurnstileWhoseSecretIsGone() {
        when(captcha.provider()).thenReturn("turnstile");
        when(captcha.locked()).thenReturn(false);
        assertThat(cmd.execute("reset")).isZero();
        verify(captcha).turnOff();
        assertThat(out.toString()).contains("CAPTCHA was switched off because its secret was deleted");
    }

    @Test
    void resetLeavesAnEnvironmentLockedTurnstileAndSaysSo() {
        when(captcha.provider()).thenReturn("turnstile");
        when(captcha.locked()).thenReturn(true);
        assertThat(cmd.execute("reset")).isZero();
        verify(captcha, never()).turnOff();
        assertThat(err.toString().strip())
            .isEqualTo("STILLPRESS_CAPTCHA_PROVIDER is set and overrides this; unset it and restart.");
        assertThat(out.toString()).doesNotContain("switched off");
    }
}
