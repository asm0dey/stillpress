package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.webauthn.WebAuthnEndpointHelper;
import io.quarkus.test.security.webauthn.WebAuthnHardware;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Limiters;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class PasskeyLoginTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    TwoFactorRepository factors;
    @TestHTTPResource
    URL url;

    record Challenge(String challenge, String cookie) {}

    record Key(WebAuthnHardware hardware, String id) {}

    @BeforeEach
    void reset() {
        store.reset();
        Limiters.reset();
    }

    long user(String login) {
        var id = users.create(login, login + "@example.org", login, Role.ADMINISTRATOR).id();
        users.setPassword(id, TestStore.PASSWORD_HASH);
        return id;
    }

    Key register(long user, String login) {
        var r = RestAssured
            .given()
            .queryParam("username", login)
            .get("/q/webauthn/register-options-challenge")
            .then()
            .statusCode(200)
            .extract();
        var hardware = new WebAuthnHardware(url);
        String id = csrf()
            .cookie("sp_session", sessions.create(user))
            .cookie(WebAuthnEndpointHelper.getChallengeCookie(), r.cookie(WebAuthnEndpointHelper.getChallengeCookie()))
            .contentType(JSON)
            .body(new JsonObject()
                .put("name", "Key")
                .put("credential", hardware.makeRegistrationJson(r.path("challenge")))
                .encode()
            )
            .post("/admin/api/2fa/passkeys")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
        return new Key(hardware, id);
    }

    static Challenge loginChallenge() {
        var r = RestAssured.given().get("/q/webauthn/login-options-challenge").then().statusCode(200).extract();
        return new Challenge(r.path("challenge"), r.cookie(WebAuthnEndpointHelper.getChallengeCookie()));
    }

    static ValidatableResponse post(String path, Challenge c, JsonObject assertion, String secondStep) {
        var r = csrf()
            .cookie(WebAuthnEndpointHelper.getChallengeCookie(), c.cookie())
            .contentType(JSON)
            .body(assertion.encode());
        return (secondStep == null ? r : r.cookie("sp_2fa", secondStep)).post(path).then();
    }

    static String passwordStep(String login) {
        return TwoStepLoginTest
            .login(login, "correct horse battery")
            .then()
            .statusCode(200)
            .body("passkey", equalTo(true))
            .extract()
            .cookie("sp_2fa");
    }

    @Test
    void passkeyAloneSignsIn() {
        var key = register(user("admin"), "admin");
        var c = loginChallenge();
        var session = post("/admin/api/login/passkey", c, key.hardware().makeLoginJson(c.challenge()), null)
            .statusCode(204)
            .extract()
            .cookie("sp_session");
        RestAssured
            .given()
            .cookie("sp_session", session)
            .get("/admin/api/session")
            .then()
            .statusCode(200)
            .body("login", equalTo("admin"));
        assertThat(factors.passkey(key.id())).hasValueSatisfying(p -> {
            assertThat(p.lastUsedAt()).isNotNull();
            assertThat(p.signCount()).isEqualTo(2L);
        });
    }

    @Test
    void passkeyFinishesTheSecondStep() {
        var key = register(user("admin"), "admin");
        var half = passwordStep("admin");
        var c = loginChallenge();
        var r = post("/admin/api/login/passkey-step", c, key.hardware().makeLoginJson(c.challenge()), half)
            .statusCode(204)
            .extract();
        assertThat(r.cookies()).containsKey("sp_session");
        assertThat(r.cookie("sp_2fa")).isEmpty();
    }

    @Test
    void anotherUsersPasskeyFailsTheSecondStep() {
        register(user("alice"), "alice");
        var bob = register(user("bob"), "bob");
        var half = passwordStep("alice");
        var c = loginChallenge();
        var r = post("/admin/api/login/passkey-step", c, bob.hardware().makeLoginJson(c.challenge()), half)
            .statusCode(401)
            .body("error", equalTo("invalid_passkey"))
            .extract();
        assertThat(r.cookies()).doesNotContainKey("sp_session");
    }

    @Test
    void forgedAssertionIs401() {
        var key = register(user("admin"), "admin");
        var c = loginChallenge();
        var assertion = key.hardware().makeLoginJson(c.challenge());
        var response = assertion.getJsonObject("response");
        var signature = Base64.getUrlDecoder().decode(response.getString("signature"));
        signature[signature.length - 1] ^= 1;
        response.put("signature", Base64.getUrlEncoder().encodeToString(signature));
        post("/admin/api/login/passkey", c, assertion, null)
            .statusCode(401)
            .body("error", equalTo("invalid_passkey"))
            .extract();
        // nineteen more failures from this IP make twenty
        for (int i = 0; i < 19; i++) {
            post("/admin/api/login/passkey", c, new JsonObject(), null).statusCode(401);
        }
        var fresh = loginChallenge();
        post("/admin/api/login/passkey", fresh, key.hardware().makeLoginJson(fresh.challenge()), null)
            .statusCode(429)
            .body("error", equalTo("rate_limited"));
    }

    @Test
    void replayedPasskeyAssertionIs401() {
        var key = register(user("admin"), "admin");
        var c = loginChallenge();
        var assertion = key.hardware().makeLoginJson(c.challenge());
        post("/admin/api/login/passkey", c, assertion, null).statusCode(204);
        // a synced passkey keeps its counter at 0, so the counter check cannot stop the replay
        factors.usePasskey(key.id(), 0);
        var r = post("/admin/api/login/passkey", c, assertion, null)
            .statusCode(401)
            .body("error", equalTo("invalid_passkey"))
            .extract();
        assertThat(r.cookies()).doesNotContainKey("sp_session");
    }

    @Test
    void replayedSecondStepAssertionIs401() {
        var key = register(user("admin"), "admin");
        var c = loginChallenge();
        var assertion = key.hardware().makeLoginJson(c.challenge());
        post("/admin/api/login/passkey-step", c, assertion, passwordStep("admin")).statusCode(204);
        factors.usePasskey(key.id(), 0);
        post("/admin/api/login/passkey-step", c, assertion, passwordStep("admin"))
            .statusCode(401)
            .body("error", equalTo("invalid_passkey"));
    }
}
