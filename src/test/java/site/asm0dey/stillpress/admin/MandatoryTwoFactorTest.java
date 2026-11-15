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
        admin = users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).getId();
        editor = users.create("editor", "editor@example.org", "Editor", Role.EDITOR).getId();
        author = users.create("author", "author@example.org", "Author", Role.AUTHOR).getId();
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
        as(sessions.create(editor)).get(POLICY).then().statusCode(403);
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
        as(session).get("/admin/api/items?type=post").then().statusCode(403).body("error", equalTo("enroll_2fa"));
        as(session).get("/admin/preview/1").then().statusCode(403).body("error", equalTo("enroll_2fa"));
        as(session).get("/admin/api/2fa/../items?type=post").then().statusCode(403);
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
            .body(Map.of("code", TwoFactorResourceTest.code(secret, clock.instant())))
            .post("/admin/api/2fa/totp/confirm")
            .then()
            .statusCode(204);
        as(session).get("/admin/api/session").then().statusCode(200).body("enrollRequired", equalTo(false));
        as(session).get("/admin/api/items?type=post").then().statusCode(200);
        as(session).post("/admin/api/logout").then().statusCode(204);
    }

    @Test
    void setPasswordGoesToEnrollment() {
        require();
        var invited = users.create("invited", "invited@example.org", "Invited", Role.AUTHOR).getId();
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
