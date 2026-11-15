package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.Altchas;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Limiters;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * With ALTCHA on, every public admin form needs a solved challenge.
 */
@QuarkusTest
class CaptchaEnforcementTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    SetupToken token;
    @Inject
    Secrets secrets;

    @BeforeEach
    void reset() {
        store.reset();
        Limiters.reset();
        settings.put("captcha_provider", "altcha");
    }

    @Test
    void loginNeedsTheCaptcha() {
        ada();
        post("login", Map.of("login", "ada", "password", TestStore.PASSWORD))
            .then()
            .statusCode(400)
            .body("error", equalTo("captcha_failed"));
        post("login", Map.of("login", "ada", "password", TestStore.PASSWORD, "captcha", Altchas.solved()))
            .then()
            .statusCode(204);
    }

    @Test
    void honeypotFailsASolvedLogin() {
        ada();
        post(
                "login",
                Map.of("login", "ada", "password", TestStore.PASSWORD, "captcha", Altchas.solved(), "website", "x")
        )
            .then()
            .statusCode(400)
            .body("error", equalTo("captcha_failed"));
    }

    @Test
    void setPasswordNeedsTheCaptcha() {
        var body = Map.of("token", "nope", "password", "correct horse battery");
        post("set-password", body).then().statusCode(400).body("error", equalTo("captcha_failed"));
        post("set-password", with(body)).then().statusCode(410).body("error", equalTo("invalid_token"));
    }

    @Test
    void resetNeedsTheCaptcha() {
        var body = Map.of("login", "ada");
        post("password-reset", body).then().statusCode(400).body("error", equalTo("captcha_failed"));
        post("password-reset", with(body)).then().statusCode(204);
    }

    @Test
    void setupNeedsTheCaptchaAfterTheToken() {
        token.regenerate();
        var body = Map.of(
                "token",
                token.current().orElseThrow(),
                "title",
                "My Site",
                "language",
                "en-GB",
                "login",
                "admin0",
                "email",
                "admin0@example.org",
                "displayName",
                "Admin",
                "password",
                "correct horse battery"
        );
        var wrongToken = new HashMap<>(body);
        wrongToken.put("token", "not-the-token");
        post("setup", wrongToken).then().statusCode(403);
        post("setup", body).then().statusCode(400).body("error", equalTo("captcha_failed"));
        post("setup", with(body)).then().statusCode(201);
    }

    @Test
    void loginCaptchaFailureDoesNotCountAgainstTheAccount() {
        ada();
        for (var i = 0; i < 6; i++) {
            post("login", Map.of("login", "ada", "password", "wrong")).then().statusCode(400);
        }
        post("login", Map.of("login", "ada", "password", TestStore.PASSWORD, "captcha", Altchas.solved()))
            .then()
            .statusCode(204);
    }

    @Test
    void captchaFailuresCountAgainstTheIp() {
        settings.put("captcha_provider", "turnstile");
        secrets.put("turnstile_secret", "s");
        var wm = new WireMockServer(WireMockConfiguration.options().port(Altchas.TURNSTILE_PORT));
        wm.start();
        try {
            wm.stubFor(WireMock.post("/siteverify").willReturn(WireMock.okJson("{\"success\":false}")));
            // each path from its own IP, so the buckets do not mix
            blockedAfter20(wm, "login", Map.of("login", "ada", "password", "x"), "203.0.113.1");
            blockedAfter20(wm, "password-reset", Map.of("login", "ada"), "203.0.113.2");
            blockedAfter20(wm, "set-password", Map.of("token", "t", "password", "correct horse battery"), "203.0.113.3");
        } finally {
            wm.stop();
        }
    }

    /**
     * Twenty bad tokens cost twenty siteverify calls; the 21st request is refused before any.
     */
    private static void blockedAfter20(WireMockServer wm, String path, Map<String, String> body, String ip) {
        wm.resetRequests();
        var bad = new HashMap<>(body);
        bad.put("captcha", "garbage");
        for (var i = 0; i < 20; i++) {
            post(path, bad, ip).then().statusCode(400).body("error", equalTo("captcha_failed"));
        }
        post(path, bad, ip).then().statusCode(429);
        wm.verify(WireMock.exactly(20), WireMock.postRequestedFor(WireMock.urlEqualTo("/siteverify")));
    }

    private void ada() {
        users.setPassword(users.create("ada", "ada@example.org", "Ada", Role.AUTHOR).getId(), TestStore.PASSWORD_HASH);
    }

    private static Map<String, String> with(Map<String, String> body) {
        var solved = new HashMap<>(body);
        solved.put("captcha", Altchas.solved());
        return solved;
    }

    private static Response post(String path, Map<String, String> body) {
        return csrf().contentType(JSON).body(body).post("/admin/api/" + path);
    }

    private static Response post(String path, Map<String, String> body, String ip) {
        return csrf().contentType(JSON).header("X-Forwarded-For", ip).body(body).post("/admin/api/" + path);
    }
}
