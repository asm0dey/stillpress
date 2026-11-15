package site.asm0dey.stillpress.account;

import static site.asm0dey.stillpress.Csrf.csrf;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The provider set in the environment: one restart covers both the admin screen and the CLI.
 */
@QuarkusTest
@TestProfile(CaptchaCommandTest.EnvProvider.class)
class CaptchaCommandTest {
    public static class EnvProvider implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("stillpress.captcha.provider", "altcha");
        }
    }

    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    SettingRepository settings;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void cliWarnsWhenEnvOverrides() {
        settings.put("captcha_provider", "turnstile");
        var cmd = new CommandLine(CaptchaCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        assertThat(cmd.execute("none")).isZero();
        assertThat(out.toString().strip()).isEqualTo("CAPTCHA is now off.");
        assertThat(err.toString().strip())
            .isEqualTo("STILLPRESS_CAPTCHA_PROVIDER is set and overrides this; unset it and restart.");
        assertThat(settings.get("captcha_provider")).hasValue("none");
    }

    @Test
    void envProviderLocksTheSetting() {
        var admin = sessions.create(users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).getId());
        csrf()
            .contentType(JSON)
            .cookie("sp_session", admin)
            .body(Map.of("provider", "none"))
            .put("/admin/api/captcha")
            .then()
            .statusCode(409)
            .body("error", equalTo("captcha_locked"));
        csrf()
            .cookie("sp_session", admin)
            .get("/admin/api/captcha")
            .then()
            .body("provider", equalTo("altcha"))
            .body("locked", equalTo(true));
        assertThat(settings.get("captcha_provider")).isEmpty();
    }
}
