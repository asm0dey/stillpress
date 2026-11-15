package site.asm0dey.stillpress.account;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.Altchas;
import site.asm0dey.stillpress.StillpressCommand.ServeCommand;
import site.asm0dey.stillpress.store.SettingRepository;

@ExtendWith(MockitoExtension.class)
class CaptchaTest {
    final WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    @Mock
    SettingRepository settings;
    @Mock
    Secrets secrets;
    Captcha captcha;

    @BeforeEach
    void start() {
        wm.start();
        captcha = withEnv(null, null, null);
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private Captcha withEnv(String provider, String siteKey, String secret) {
        return new Captcha(
                Optional.ofNullable(provider),
                Optional.ofNullable(siteKey),
                Optional.ofNullable(secret),
                URI.create(wm.baseUrl() + "/siteverify"),
                Duration.ofSeconds(1),
                settings,
                secrets
        );
    }

    @Test
    void unknownEnvProviderIsAStartupProblem() {
        assertThat(withEnv("recaptcha", null, null).environmentProblem())
            .hasValueSatisfying(p -> assertThat(p).contains("must be none, turnstile or altcha: recaptcha"));
        assertThat(withEnv("altcha", null, null).environmentProblem()).isEmpty();
        assertThat(withEnv(null, null, null).environmentProblem()).isEmpty();
    }

    @Test
    void envTurnstileWithoutKeysIsAStartupProblem() {
        assertThat(withEnv("turnstile", "site", null).environmentProblem())
            .hasValueSatisfying(p -> assertThat(p)
                .contains("STILLPRESS_TURNSTILE_SITE_KEY")
                .contains("STILLPRESS_TURNSTILE_SECRET"));
        assertThat(withEnv("turnstile", " ", "secret").environmentProblem()).isPresent();
        assertThat(withEnv("turnstile", "site", "secret").environmentProblem()).isEmpty();
    }

    @Test
    void serveExitsOnEnvProblem() {
        var err = new StringWriter();
        var bad = withEnv("recaptcha", null, null);
        assertThat(ServeCommand.check(bad, new PrintWriter(err, true))).isOne();
        assertThat(err.toString()).contains("must be none, turnstile or altcha: recaptcha");
        assertThat(ServeCommand.check(withEnv("none", null, null), new PrintWriter(err, true))).isZero();
    }

    @Test
    void noneAcceptsAnything() {
        assertThatCode(() -> captcha.verify(null, null, "203.0.113.1")).doesNotThrowAnyException();
        assertThatCode(() -> captcha.verify("garbage", "", "203.0.113.1")).doesNotThrowAnyException();
    }

    @Test
    void honeypotFailsUnderEveryProvider() {
        for (var provider : List.of("none", "altcha", "turnstile")) {
            var c = withEnv(provider, "site", "secret");
            assertThatThrownBy(() -> c.verify("t", "http://spam.example", "203.0.113.1"))
                .isInstanceOf(CaptchaFailedException.class);
        }
    }

    @Test
    void altchaRoundTrip() throws Exception {
        when(secrets.derive("altcha-hmac", 32)).thenReturn(new byte[32]);
        var altcha = withEnv("altcha", null, null);
        var payload = Altchas.solve(altcha.challenge());
        assertThatCode(() -> altcha.verify(payload, null, "203.0.113.1")).doesNotThrowAnyException();
        var json = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        var tampered = Base64
            .getEncoder()
            .encodeToString(json.replaceFirst("\"number\":\\d+", "\"number\":100001").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> altcha.verify(tampered, null, "203.0.113.1")).isInstanceOf(
                CaptchaFailedException.class
        );
        assertThatThrownBy(() -> altcha.verify(null, null, "203.0.113.1")).isInstanceOf(CaptchaFailedException.class);
    }

    @Test
    void turnstileUsesVerifyUrl() {
        when(settings.get("captcha_provider")).thenReturn(Optional.of("turnstile"));
        when(secrets.get("turnstile_secret")).thenReturn(Optional.of("the-secret"));
        wm.stubFor(post("/siteverify")
            .withRequestBody(containing("secret=the-secret"))
            .withRequestBody(containing("response=good"))
            .withRequestBody(containing("remoteip=203.0.113.1"))
            .willReturn(okJson("{\"success\" : true}"))
        );
        wm.stubFor(post("/siteverify")
            .withRequestBody(containing("response=bad"))
            .willReturn(okJson("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}"))
        );
        wm.stubFor(post("/siteverify")
            .withRequestBody(containing("response=slow"))
            .willReturn(okJson("{\"success\":true}").withFixedDelay(3000))
        );
        assertThatCode(() -> captcha.verify("good", null, "203.0.113.1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> captcha.verify("bad", null, "203.0.113.1")).isInstanceOf(CaptchaFailedException.class);
        assertThatThrownBy(() -> captcha.verify("slow", null, "203.0.113.1")).isInstanceOf(CaptchaFailedException.class);
    }
}
