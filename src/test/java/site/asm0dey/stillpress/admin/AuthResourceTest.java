package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static io.restassured.matcher.RestAssuredMatchers.detailedCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Limiters;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;

@QuarkusTest
class AuthResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    TokenRepository tokens;
    @Inject
    SettingRepository settings;
    long adminId;

    @BeforeEach
    void reset() {
        store.reset();
        Limiters.reset();
        adminId = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).getId();
        users.setPassword(adminId, Passwords.hash("correct horse battery"));
    }

    @Test
    void loginSetsHttpOnlyLaxCookie() {
        login("admin", "correct horse battery")
            .then()
            .statusCode(204)
            .cookie("sp_session", detailedCookie().httpOnly(true).sameSite("Lax").path("/").secured(false));
    }

    @Test
    void loginByEmailWorks() {
        login("a@example.org", "correct horse battery").then().statusCode(204);
    }

    @Test
    void unknownAndWrongGiveSameAnswer() {
        var a = login("nobody", "correct horse battery").then().statusCode(401).extract().asString();
        var b = login("admin", "wrong horse battery!").then().statusCode(401).extract().asString();
        assertThat(a).isEqualTo(b).isEqualTo("{\"error\":\"invalid_credentials\"}");
    }

    @Test
    void missingFieldsAre401() {
        for (var body : List.of(Map.of("login", "admin"), Map.of("password", "correct horse battery"), Map.of())) {
            csrf()
                .contentType(JSON)
                .body(body)
                .post("/admin/api/login")
                .then()
                .statusCode(401)
                .body("error", equalTo("invalid_credentials"));
        }
    }

    @Test
    void oversizedCredentialsAre401AndCountAgainstTheIpOnly() {
        var longPassword = "😀".repeat(1025);
        for (int i = 0; i < 6; i++) {
            login("admin", longPassword).then().statusCode(401).body("error", equalTo("invalid_credentials"));
        }
        // six failures would have locked the account; these did not touch it
        login("admin", "correct horse battery").then().statusCode(204);
        for (int i = 0; i < 14; i++) {
            login("a".repeat(101), "x").then().statusCode(401).body("error", equalTo("invalid_credentials"));
        }
        // twenty from this IP
        login("admin", "correct horse battery").then().statusCode(429);
    }

    @Test
    void passwordAtTheLengthLimitTakesTheNormalPath() {
        login("a".repeat(100), "x").then().statusCode(401);
        for (int i = 0; i < 5; i++) {
            login("admin", "😀".repeat(1024)).then().statusCode(401);
        }
        // counted against the account like any wrong password
        login("admin", "correct horse battery").then().statusCode(429);
    }

    @Test
    void malformedStoredHashIs401() {
        users.setPassword(adminId, "not-a-hash");
        login("admin", "correct horse battery").then().statusCode(401);
    }

    @Test
    void sixthAttemptIsRateLimitedEvenWithRightPassword() {
        for (int i = 0; i < 5; i++) {
            login("admin", "wrong horse battery!").then().statusCode(401);
        }
        login("admin", "correct horse battery")
            .then()
            .statusCode(429)
            .header("Retry-After", notNullValue())
            .body("error", equalTo("rate_limited"));
    }

    @Test
    void ipLimitUsesTheForwardedClientAddress() {
        for (int i = 0; i < 20; i++) {
            csrf()
                .header("X-Forwarded-For", "203.0.113.7")
                .contentType(JSON)
                .body(Map.of("login", "user" + i, "password", "wrong horse battery!"))
                .post("/admin/api/login")
                .then()
                .statusCode(401);
        }
        csrf()
            .header("X-Forwarded-For", "203.0.113.7")
            .contentType(JSON)
            .body(Map.of("login", "admin", "password", "correct horse battery"))
            .post("/admin/api/login")
            .then()
            .statusCode(429);
        login("admin", "correct horse battery").then().statusCode(204);
    }

    @Test
    void importedUserWithoutPasswordCannotLogIn() {
        users.create("imported", "i@example.org", "Imported", Role.AUTHOR);
        login("imported", "correct horse battery").then().statusCode(401);
    }

    @Test
    void secureCookieBehindHttpsProxy() {
        csrf()
            .header("X-Forwarded-Proto", "https")
            .contentType(JSON)
            .body(Map.of("login", "admin", "password", "correct horse battery"))
            .post("/admin/api/login")
            .then()
            .statusCode(204)
            .cookie("sp_session", detailedCookie().secured(true));
    }

    @Test
    void sessionDescribesTheUser() {
        var cookie = login("admin", "correct horse battery").cookie("sp_session");
        given()
            .cookie("sp_session", cookie)
            .get("/admin/api/session")
            .then()
            .statusCode(200)
            .body("id", equalTo((int) adminId))
            .body("login", equalTo("admin"))
            .body("displayName", equalTo("Admin"))
            .body("email", equalTo("a@example.org"))
            .body("role", equalTo("administrator"));
        given().get("/admin/api/session").then().statusCode(401);
    }

    @Test
    void localeDefaultsFromSiteLanguageAndCanBeChanged() {
        var language = settings.get("language");
        try {
            localeFlow();
        } finally {
            // leave the site language and the admin's locale as other tests expect them
            language.ifPresentOrElse(l -> settings.put("language", l), () -> settings.delete("language"));
            users.setLocale(adminId, null);
        }
    }

    private void localeFlow() {
        var cookie = login("admin", "correct horse battery").cookie("sp_session");
        given().cookie("sp_session", cookie).get("/admin/api/session").then().body("locale", equalTo("en"));
        settings.put("language", "ru-RU");
        given().cookie("sp_session", cookie).get("/admin/api/session").then().body("locale", equalTo("ru"));
        putLocale(cookie, Map.of("locale", "en")).then().statusCode(204);
        given().cookie("sp_session", cookie).get("/admin/api/session").then().body("locale", equalTo("en"));
        putLocale(cookie, Map.of("locale", "ru")).then().statusCode(204);
        given().cookie("sp_session", cookie).get("/admin/api/session").then().body("locale", equalTo("ru"));
        for (var body : List.<Map<String, String>>of(Map.of("locale", "de"), Map.of("locale", "ru-RU"), Map.of())) {
            putLocale(cookie, body).then().statusCode(400).body("error", equalTo("invalid_locale"));
        }
        assertThat(users.findById(adminId).orElseThrow().getLocale()).isEqualTo("ru");
        csrf().contentType(JSON).body(Map.of("locale", "en")).put("/admin/api/session/locale").then().statusCode(401);
    }

    private static Response putLocale(String cookie, Map<String, String> body) {
        return csrf().cookie("sp_session", cookie).contentType(JSON).body(body).put("/admin/api/session/locale");
    }

    @Test
    void logoutKillsTheSession() {
        var cookie = login("admin", "correct horse battery").cookie("sp_session");
        csrf()
            .cookie("sp_session", cookie)
            .post("/admin/api/logout")
            .then()
            .statusCode(204)
            .cookie("sp_session", detailedCookie().value("").maxAge(0));
        given().cookie("sp_session", cookie).get("/admin/api/session").then().statusCode(401);
        // anonymous logout is fine too
        csrf().post("/admin/api/logout").then().statusCode(204);
    }

    @Test
    void setPasswordTokenIsSingleUse() {
        var raw = tokens.create(adminId, "set_password", Duration.ofHours(24));
        // setting a password does not log in
        assertThat(setPassword(raw, "a brand new password").then().statusCode(204).extract().cookies())
            .doesNotContainKey("sp_session");
        setPassword(raw, "another new password").then().statusCode(410).body("error", equalTo("invalid_token"));
        login("admin", "a brand new password").then().statusCode(204);
    }

    @Test
    void setPasswordRevokesExistingSessions() {
        var cookie = login("admin", "correct horse battery").cookie("sp_session");
        var raw = tokens.create(adminId, "set_password", Duration.ofHours(24));
        setPassword(raw, "a brand new password").then().statusCode(204);
        given().cookie("sp_session", cookie).get("/admin/api/session").then().statusCode(401);
    }

    @Test
    void accountLimitCoversLoginAndEmail() {
        for (int i = 0; i < 3; i++) {
            login("admin", "wrong horse battery!").then().statusCode(401);
        }
        for (int i = 0; i < 2; i++) {
            login("A@example.org", "wrong horse battery!").then().statusCode(401);
        }
        login("admin", "correct horse battery").then().statusCode(429);
        login("a@example.org", "correct horse battery").then().statusCode(429);
    }

    @Test
    void successClearsTheAccountWhicheverNameWasUsed() {
        for (int i = 0; i < 4; i++) {
            login("admin", "wrong horse battery!").then().statusCode(401);
        }
        login("a@example.org", "correct horse battery").then().statusCode(204);
        for (int i = 0; i < 4; i++) {
            login("admin", "wrong horse battery!").then().statusCode(401);
        }
        login("admin", "correct horse battery").then().statusCode(204);
    }

    @Test
    void weakPasswordKeepsTheToken() {
        var raw = tokens.create(adminId, "set_password", Duration.ofHours(24));
        setPassword(raw, "short").then().statusCode(400).body("error", equalTo("weak_password"));
        setPassword(raw, "a brand new password").then().statusCode(204);
    }

    @Test
    void tokenForAnotherPurposeIsRejected() {
        var raw = tokens.create(adminId, "other", Duration.ofHours(24));
        setPassword(raw, "a brand new password").then().statusCode(410);
        setPassword(null, "a brand new password").then().statusCode(410);
    }

    @Test
    void wpAdminRedirects() {
        for (var path : List.of("/wp-login.php", "/wp-admin", "/wp-admin/", "/wp-admin/post.php")) {
            given().redirects().follow(false).get(path).then().statusCode(302).header("Location", "/admin/");
        }
    }

    private static Response login(String login, String password) {
        return csrf().contentType(JSON).body(Map.of("login", login, "password", password)).post("/admin/api/login");
    }

    private static Response setPassword(String token, String password) {
        var body = new HashMap<String, String>();
        body.put("token", token);
        body.put("password", password);
        return csrf().contentType(JSON).body(body).post("/admin/api/set-password");
    }
}
