package site.asm0dey.stillpress.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static site.asm0dey.stillpress.Csrf.csrf;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.Altchas;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SecretRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class CaptchaResourceQuarkusTest {
    final WireMockServer wm = new WireMockServer(WireMockConfiguration.options().port(Altchas.TURNSTILE_PORT));
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    Secrets secrets;
    @Inject
    SecretRepository raw;
    String admin;

    @BeforeEach
    void start() {
        store.reset();
        wm.start();
        admin = sessions.create(users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).id());
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void defaultsToNone() {
        get().then().statusCode(200).body("provider", equalTo("none")).body("locked", equalTo(false));
        RestAssured
            .get("/admin/api/login-options")
            .then()
            .body("captcha", equalTo("none"))
            .body("turnstileSiteKey", nullValue());
    }

    @Test
    void matchingPairIsSaved() {
        wm.stubFor(post("/siteverify")
            .withRequestBody(containing("secret=good-secret"))
            .withRequestBody(containing("response=solved"))
            .willReturn(okJson("{\"success\":true}"))
        );
        put("turnstile", "site-key", "good-secret", "solved")
            .then()
            .statusCode(200)
            .body("provider", equalTo("turnstile"))
            .body("turnstileSiteKey", equalTo("site-key"))
            .body("secretSet", equalTo(true));
        assertThat(secrets.get("turnstile_secret")).hasValue("good-secret");
        assertThat(raw.ciphertext("turnstile_secret")).hasValueSatisfying(c -> assertThat(c).doesNotContain("good"));
        wm.verify(1, postRequestedFor(urlEqualTo("/siteverify")).withRequestBody(notMatching(".*remoteip.*")));
        RestAssured
            .get("/admin/api/login-options")
            .then()
            .body("captcha", equalTo("turnstile"))
            .body("turnstileSiteKey", equalTo("site-key"));
        // a null secret keeps the stored one, and the new site key is checked against it again
        put("turnstile", "other-key", null, "solved").then().statusCode(200).body(
                "turnstileSiteKey",
                equalTo("other-key")
        );
        wm.verify(2, postRequestedFor(urlEqualTo("/siteverify")).withRequestBody(containing("secret=good-secret")));
        put("none", null, null, null).then().statusCode(200).body("provider", equalTo("none"));
    }

    @Test
    void challenge404UnlessAltcha() {
        RestAssured.get("/admin/api/altcha/challenge").then().statusCode(404);
        put("altcha", null, null, null).then().statusCode(200).body("provider", equalTo("altcha"));
        RestAssured
            .get("/admin/api/altcha/challenge")
            .then()
            .statusCode(200)
            .body("algorithm", equalTo("SHA-256"))
            .body("maxnumber", equalTo(100000));
    }

    @Test
    void needsAnAdministrator() {
        var author = sessions.create(users.create("w", "w@example.org", "W", Role.AUTHOR).id());
        AuthMatrix.forbiddenFor(author, "GET", "/admin/api/captcha");
        AuthMatrix.forbiddenFor(author, "PUT", "/admin/api/captcha");
        AuthMatrix.anonymousIs401("GET", "/admin/api/captcha");
        AuthMatrix.anonymousIs401("PUT", "/admin/api/captcha");
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        // with a token this body answers 200 (see matchingPairIsSaved), so only the CSRF filter can refuse it
        AuthMatrix.missingCsrfIs400(admin, "PUT", "/admin/api/captcha", Map.of("provider", "altcha"));
        assertThat(settings.get("captcha_provider")).isEmpty();
    }

    private Response get() {
        return csrf().cookie("sp_session", admin).get("/admin/api/captcha");
    }

    private Response put(String provider, String siteKey, String secret, String token) {
        var body = new HashMap<String, String>();
        body.put("provider", provider);
        body.put("turnstileSiteKey", siteKey);
        body.put("turnstileSecret", secret);
        body.put("turnstileToken", token);
        return csrf().contentType(JSON).cookie("sp_session", admin).body(body).put("/admin/api/captcha");
    }
}
