package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.webauthn.WebAuthnEndpointHelper;
import io.quarkus.test.security.webauthn.WebAuthnHardware;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passkeys;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class PasskeyRegistrationTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    TwoFactorRepository factors;
    @Inject
    Passkeys passkeys;
    @TestHTTPResource
    URL url;
    String session;
    long user;

    @BeforeEach
    void login() {
        store.reset();
        user = users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).getId();
        session = sessions.create(user);
    }

    RequestSpecification asAdmin() {
        return csrf().cookie("sp_session", session);
    }

    record Challenge(String challenge, String cookie) {}

    Challenge challengeFor(String login) {
        var r = RestAssured
            .given()
            .queryParam("username", login)
            .get("/q/webauthn/register-options-challenge")
            .then()
            .statusCode(200)
            .extract();
        return new Challenge(r.path("challenge"), r.cookie(WebAuthnEndpointHelper.getChallengeCookie()));
    }

    ValidatableResponse register(Challenge c, String name) {
        return post(c, name, new WebAuthnHardware(url).makeRegistrationJson(c.challenge()));
    }

    ValidatableResponse post(Challenge c, String name, JsonObject credential) {
        return asAdmin()
            .cookie(WebAuthnEndpointHelper.getChallengeCookie(), c.cookie())
            .contentType(JSON)
            .body(new JsonObject().put("name", name).put("credential", credential).encode())
            .post("/admin/api/2fa/passkeys")
            .then();
    }

    @Test
    void registerListAndDelete() {
        String id = register(challengeFor("admin"), " Laptop ")
            .statusCode(201)
            .body("name", equalTo("Laptop"))
            .extract()
            .path("id");
        asAdmin()
            .get("/admin/api/2fa")
            .then()
            .statusCode(200)
            .body("passkeys.id", equalTo(List.of(id)))
            .body("passkeys[0].name", equalTo("Laptop"));
        var record = passkeys.findByCredentialId(id).await().atMost(Duration.ofSeconds(5));
        assertThat(record.getUsername()).isEqualTo("admin");
        assertThat(record.getCredentialID()).isEqualTo(id);
        assertThat(passkeys.findByUsername("admin").await().atMost(Duration.ofSeconds(5)))
            .extracting(WebAuthnCredentialRecord::getCredentialID)
            .containsExactly(id);
        asAdmin().delete("/admin/api/2fa/passkeys/{id}", id).then().statusCode(204);
        assertThatThrownBy(() -> passkeys.findByCredentialId(id).await().atMost(Duration.ofSeconds(5)))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(factors.passkeys(user)).isEmpty();
        asAdmin().delete("/admin/api/2fa/passkeys/{id}", id).then().statusCode(404);
    }

    @Test
    void passkeyIsStoredForTheSessionUser() {
        var other = users.create("other", "other@example.org", "Other", Role.ADMINISTRATOR).getId();
        String id = register(challengeFor("other"), "Key").statusCode(201).extract().path("id");
        assertThat(factors.passkey(id)).hasValueSatisfying(p -> assertThat(p.getUserId()).isEqualTo(user));
        assertThat(factors.passkeys(other)).isEmpty();
    }

    @Test
    void replayedRegistrationIs400() {
        var c = challengeFor("admin");
        var credential = new WebAuthnHardware(url).makeRegistrationJson(c.challenge());
        post(c, "Key", credential).statusCode(201);
        // the challenge lives only in the client's cookie, so a replay verifies again; the stored credential stops it
        post(c, "Key", credential).statusCode(400).body("error", equalTo("invalid_passkey"));
        assertThat(factors.passkeys(user)).hasSize(1);
    }

    @Test
    void forgedCredentialIs400() {
        var c = challengeFor("admin");
        asAdmin()
            .cookie(WebAuthnEndpointHelper.getChallengeCookie(), c.cookie())
            .contentType(JSON)
            .body(Map.of("name", "Key", "credential", Map.of("id", "x", "type", "public-key")))
            .post("/admin/api/2fa/passkeys")
            .then()
            .statusCode(400)
            .body("error", equalTo("invalid_passkey"));
        assertThat(factors.passkeys(user)).isEmpty();
    }

    @Test
    void blankOrLongNameIs400() {
        register(challengeFor("admin"), "  ")
            .statusCode(400)
            .body("error", equalTo("invalid"))
            .body("field", equalTo("name"));
        register(challengeFor("admin"), "x".repeat(101)).statusCode(400).body("field", equalTo("name"));
        // the limit is in UTF-16 units, as the column stores them: 100 key emoji are 200, refused rather than a 500
        register(challengeFor("admin"), "🔑".repeat(100)).statusCode(400).body("field", equalTo("name"));
        register(challengeFor("admin"), "🔑".repeat(50)).statusCode(201);
        assertThat(factors.passkeys(user)).hasSize(1);
    }

    @Test
    void anonymousIs401() {
        csrf().contentType(JSON).body(Map.of()).post("/admin/api/2fa/passkeys").then().statusCode(401);
    }
}
