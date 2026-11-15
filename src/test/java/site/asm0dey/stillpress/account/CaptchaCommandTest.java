package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class CaptchaCommandTest {
    @Mock
    Captcha captcha;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(CommandLine.Model.CommandSpec.create());
        cmd.addSubcommand("none", new CaptchaCommand.None(captcha));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
    }

    @Test
    void noneTurnsTheCaptchaOff() {
        when(captcha.locked()).thenReturn(false);
        assertThat(cmd.execute("none")).isZero();
        verify(captcha).turnOff();
        assertThat(out.toString().strip()).isEqualTo("CAPTCHA is now off.");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void noneWarnsWhenTheEnvironmentOverrides() {
        when(captcha.locked()).thenReturn(true);
        assertThat(cmd.execute("none")).isZero();
        verify(captcha).turnOff();
        assertThat(out.toString().strip()).isEqualTo("CAPTCHA is now off.");
        assertThat(err.toString().strip())
            .isEqualTo("STILLPRESS_CAPTCHA_PROVIDER is set and overrides this; unset it and restart.");
    }
}
