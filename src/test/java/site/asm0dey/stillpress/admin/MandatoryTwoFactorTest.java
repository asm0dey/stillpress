package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import io.restassured.specification.RequestSpecification;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Limiters;
import site.asm0dey.stillpress.account.MutableClock;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class MandatoryTwoFactorTest {
    static final String POLICY = "/admin/api/security/two-factor";
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    TwoFactorRepository factors;
    @Inject
    SettingRepository settings;
    @Inject
    TokenRepository tokens;
    @Inject
    MutableClock clock;
    long admin;
    long editor;
    long author;
    String adminSession;

    @BeforeEach
    void reset() {
        store.reset();
        Limiters.reset();
        admin = users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).id();
        editor = users.create("editor", "editor@example.org", "Editor", Role.EDITOR).id();
        author = users.create("author", "author@example.org", "Author", Role.AUTHOR).id();
        users.setPassword(editor, TestStore.PASSWORD_HASH);
        adminSession = sessions.create(admin);
    }

    static RequestSpecification as(String session) {
        return csrf().cookie("sp_session", session);
    }

    void addPasskey(long user, String credentialId) {
        factors.addPasskey(
                user,
                "key " + credentialId,
                new RequiredPersistedData(
                        "u" + user,
                        credentialId,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new byte[] {1, 2, 3, 4},
                        -7,
                        0
                )
        );
    }

    void require() {
        addPasskey(admin, "admin-key");
        as(adminSession)
            .contentType(JSON)
            .body(Map.of("required", true))
            .put(POLICY)
            .then()
            .statusCode(200)
            .body("required", equalTo(true));
    }

    @Test
    void adminWithout2faCannotRequireIt() {
        as(adminSession)
            .contentType(JSON)
            .body(Map.of("required", true))
            .put(POLICY)
            .then()
            .statusCode(409)
            .body("error", equalTo("admin_needs_2fa"));
        assertThat(settings.get("require_2fa")).isEmpty();
        as(adminSession).get(POLICY).then().statusCode(200).body("required", equalTo(false));
    }

    @Test
    void onlyAnAdministratorSetsThePolicy() {
        var session = sessions.create(editor);
        as(session).get(POLICY).then().statusCode(403);
        as(session).contentType(JSON).body(Map.of("required", false)).put(POLICY).then().statusCode(403);
    }

    @Test
    void cancellingAnUnconfirmedSetupIsNeverRefused() {
        require();
        var session = sessions.create(editor);
        as(session).post("/admin/api/2fa/totp").then().statusCode(200);
        as(session).delete("/admin/api/2fa/totp").then().statusCode(204);
        assertThat(factors.totp(editor)).isEmpty();
    }

    @Test
    void requiringSignsOutUsersWithoutFactors() {
        var editorSession = sessions.create(editor);
        addPasskey(author, "author-key");
        var authorSession = sessions.create(author);
        require();
        as(editorSession).get("/admin/api/session").then().statusCode(401);
        as(authorSession).get("/admin/api/session").then().statusCode(200).body("enrollRequired", equalTo(false));
        as(adminSession).get("/admin/api/session").then().statusCode(200);
        as(adminSession).get("/admin/api/2fa").then().statusCode(200).body("required", equalTo(true));
        // turning it off keeps sessions as they are
        as(adminSession)
            .contentType(JSON)
            .body(Map.of("required", false))
            .put(POLICY)
            .then()
            .statusCode(200)
            .body("required", equalTo(false));
        as(authorSession).get("/admin/api/session").then().statusCode(200);
    }

    @Test
    void enrollRequiredSessionIsFencedToEnrollment() throws Exception {
        require();
        var session =
                TwoStepLoginTest
            .login("editor", TestStore.PASSWORD)
            .then()
            .statusCode(204)
            .extract()
            .cookie("sp_session");
        as(session).get("/admin/api/session").then().statusCode(200).body("enrollRequired", equalTo(true));
        // the admin UI's Gate reads it on every navigation before it can send the user to /two-factor
        as(session).get("/admin/api/setup").then().statusCode(200);
        as(session).get("/admin/api/items?type=post").then().statusCode(403).body("error", equalTo("enroll_2fa"));
        as(session).get("/admin/preview/1").then().statusCode(403).body("error", equalTo("enroll_2fa"));
        // sent raw: the server, not the client, must resolve the dot segments before the fence looks at the path
        as(session)
            .urlEncodingEnabled(false)
            .get("/admin/api/2fa/%2E%2E/items?type=post")
            .then()
            .statusCode(403)
            .body("error", equalTo("enroll_2fa"));
        as(session).contentType(JSON).body(Map.of("locale", "ru")).put("/admin/api/session/locale").then().statusCode(
                204
        );
        var secret = as(session)
            .post("/admin/api/2fa/totp")
            .then()
            .statusCode(200)
            .extract()
            .path("secret")
            .toString();
        as(session)
            .contentType(JSON)
            .body(Map.of("code", TwoFactorResourceQuarkusTest.code(secret, clock.instant())))
            .post("/admin/api/2fa/totp/confirm")
            .then()
            .statusCode(204);
        as(session).get("/admin/api/session").then().statusCode(200).body("enrollRequired", equalTo(false));
        as(session).get("/admin/api/items?type=post").then().statusCode(200);
        as(session).post("/admin/api/logout").then().statusCode(204);
    }

    @Test
    void signInEndpointsPassTheFence() {
        require();
        var session =
                TwoStepLoginTest
            .login("editor", TestStore.PASSWORD)
            .then()
            .statusCode(204)
            .extract()
            .cookie("sp_session");
        var bad = Map.of("login", "nobody", "password", "wrong password", "code", "000000", "token", "nope");
        for (var path :
                List.of(
                        "/admin/api/login",
                        "/admin/api/login/totp",
                        "/admin/api/login/passkey-step",
                        "/admin/api/login/passkey",
                        "/admin/api/password-reset",
                        "/admin/api/set-password"
        )) {
            var r = as(session).contentType(JSON).body(bad).post(path);
            assertThat(r.statusCode()).as(path).isNotEqualTo(403);
            assertThat(r.body().asString()).as(path).doesNotContain("enroll_2fa");
        }
        var challenge = as(session).get("/admin/api/altcha/challenge");
        assertThat(challenge.statusCode()).isNotEqualTo(403);
        assertThat(challenge.body().asString()).doesNotContain("enroll_2fa");
        // setup reads no identity either, but it changes the site; the fence stays on it
        as(session)
            .contentType(JSON)
            .body(Map.of())
            .post("/admin/api/setup")
            .then()
            .statusCode(403)
            .body("error", equalTo("enroll_2fa"));
    }

    @Test
    void setPasswordWithAnEnrollmentSessionSignsInTheTokenUser() {
        require();
        var session =
                TwoStepLoginTest
            .login("editor", TestStore.PASSWORD)
            .then()
            .statusCode(204)
            .extract()
            .cookie("sp_session");
        var invited = users.create("invited", "invited@example.org", "Invited", Role.AUTHOR).id();
        var invite = tokens.create(invited, TokenRepository.INVITE, Duration.ofHours(24));
        var issued = as(session)
            .contentType(JSON)
            .body(Map.of("token", invite, "password", "a brand new password"))
            .post("/admin/api/set-password")
            .then()
            .statusCode(200)
            .body("enroll", equalTo(true))
            .extract()
            .cookie("sp_session");
        assertThat(issued).isNotEqualTo(session);
        as(issued).get("/admin/api/session").then().statusCode(200).body("login", equalTo("invited"));
        // the editor's own enrollment session is untouched
        as(session).get("/admin/api/session").then().statusCode(200).body("login", equalTo("editor"));
    }

    @Test
    void setPasswordGoesToEnrollment() {
        require();
        var invited = users.create("invited", "invited@example.org", "Invited", Role.AUTHOR).id();
        var invite = tokens.create(invited, TokenRepository.INVITE, Duration.ofHours(24));
        var session =
                setPassword(invite)
            .then()
            .statusCode(200)
            .body("enroll", equalTo(true))
            .extract()
            .cookie("sp_session");
        as(session).get("/admin/api/session").then().statusCode(200).body("enrollRequired", equalTo(true));
        addPasskey(author, "author-key");
        var reset = tokens.create(author, TokenRepository.SET_PASSWORD, Duration.ofHours(1));
        assertThat(setPassword(reset).then().statusCode(204).extract().cookies()).doesNotContainKey("sp_session");
    }

    @Test
    void requiringAgainKeepsEnrollmentSessions() {
        require();
        var enrolling = sessions.create(editor);
        as(adminSession)
            .contentType(JSON)
            .body(Map.of("required", true))
            .put(POLICY)
            .then()
            .statusCode(200)
            .body("required", equalTo(true));
        as(enrolling).get("/admin/api/session").then().statusCode(200).body("enrollRequired", equalTo(true));
    }

    @Test
    void anUnconfirmedSeedIsNoFactorWhenRequiring() {
        assertThat(factors.putTotp(editor, "unconfirmed")).isTrue();
        var editorSession = sessions.create(editor);
        require();
        as(editorSession).get("/admin/api/session").then().statusCode(401);
    }

    @Test
    void aConfirmedAuthenticatorAsTheLastFactorStays() {
        assertThat(factors.putTotp(editor, "seed")).isTrue();
        assertThat(factors.confirmTotp(editor, "seed", 1)).isTrue();
        var editorSession = sessions.create(editor);
        require();
        as(editorSession).delete("/admin/api/2fa/totp").then().statusCode(409).body("error", equalTo("last_factor"));
        assertThat(factors.factorCount(editor)).isEqualTo(1);
        // the delete is conditional on what was read: a seed confirmed since then is not removed as unconfirmed
        assertThat(factors.deleteTotp(editor, false)).isZero();
        assertThat(factors.factorCount(editor)).isEqualTo(1);
    }

    @Test
    void lastFactorCannotBeRemovedWhileRequired() {
        require();
        as(adminSession)
            .delete("/admin/api/2fa/passkeys/admin-key")
            .then()
            .statusCode(409)
            .body("error", equalTo("last_factor"));
        assertThat(factors.factorCount(admin)).isEqualTo(1);
        addPasskey(admin, "spare-key");
        as(adminSession).delete("/admin/api/2fa/passkeys/admin-key").then().statusCode(204);
        // an unconfirmed authenticator is no factor, so dropping it is fine
        as(adminSession).post("/admin/api/2fa/totp").then().statusCode(200);
        as(adminSession).delete("/admin/api/2fa/totp").then().statusCode(204);
        as(adminSession).contentType(JSON).body(Map.of("required", false)).put(POLICY).then().statusCode(200);
        as(adminSession).delete("/admin/api/2fa/passkeys/spare-key").then().statusCode(204);
        assertThat(factors.factorCount(admin)).isZero();
    }

    static io.restassured.response.Response setPassword(String token) {
        return csrf()
            .contentType(JSON)
            .body(Map.of("token", token, "password", "a brand new password"))
            .post("/admin/api/set-password");
    }
}
