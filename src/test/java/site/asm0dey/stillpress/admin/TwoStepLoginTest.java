package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static io.restassured.matcher.RestAssuredMatchers.detailedCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Limiters;
import site.asm0dey.stillpress.account.MutableClock;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.Totp;
import site.asm0dey.stillpress.store.AuthorRepository;

@QuarkusTest
class TwoStepLoginTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    Totp totp;
    @Inject
    MutableClock clock;
    @Inject
    DSLContext db;
    long adminId;
    String secret;

    @BeforeEach
    void reset() throws Exception {
        store.reset();
        Limiters.reset();
        adminId = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).id();
        users.setPassword(adminId, TestStore.PASSWORD_HASH);
        secret = totp.start(adminId, "admin").secret();
        assertThat(Map.of("confirm", totp.confirm(adminId, TwoFactorResourceQuarkusTest.code(secret, clock.instant()))))
            .containsExactly(Map.entry("confirm", true));
        // confirming spent the current step
        clock.advance(Duration.ofSeconds(30));
    }

    static Response login(String login, String password) {
        return csrf().contentType(JSON).body(Map.of("login", login, "password", password)).post("/admin/api/login");
    }

    static Response totpStep(String cookie, String code) {
        var r = csrf().contentType(JSON).body(Map.of("code", code));
        return (cookie == null ? r : r.cookie("sp_2fa", cookie)).post("/admin/api/login/totp");
    }

    String passwordStep() {
        return login("admin", "correct horse battery").then().statusCode(200).extract().cookie("sp_2fa");
    }

    @Test
    void passwordThenTotp() throws Exception {
        var first = login("admin", "correct horse battery")
            .then()
            .statusCode(200)
            .body("totp", equalTo(true))
            .body("passkey", equalTo(false))
            .cookie(
                    "sp_2fa",
                    detailedCookie().path("/admin/api/login").httpOnly(true).sameSite("Strict").maxAge(300).secured(
                            false
                    )
            )
            .extract();
        assertThat(first.cookies()).doesNotContainKey("sp_session");
        var session = totpStep(first.cookie("sp_2fa"), TwoFactorResourceQuarkusTest.code(secret, clock.instant()))
            .then()
            .statusCode(204)
            .cookie("sp_2fa", detailedCookie().maxAge(0))
            .extract()
            .cookie("sp_session");
        RestAssured
            .given()
            .cookie("sp_session", session)
            .get("/admin/api/session")
            .then()
            .statusCode(200)
            .body("login", equalTo("admin"));
        // the token was spent with the step
        totpStep(first.cookie("sp_2fa"), "123456").then().statusCode(401).body("error", equalTo("second_step_expired"));
    }

    @Test
    void secondStepTokenIsNotASession() {
        var half = passwordStep();
        RestAssured.given().cookie("sp_session", half).get("/admin/api/session").then().statusCode(401);
        RestAssured.given().cookie("sp_2fa", half).get("/admin/api/session").then().statusCode(401);
    }

    @Test
    void expiredSecondStepIs401() throws Exception {
        var half = passwordStep();
        db.update(USER_TOKEN).set(USER_TOKEN.EXPIRES_AT, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).execute();
        totpStep(half, TwoFactorResourceQuarkusTest.code(secret, clock.instant()))
            .then()
            .statusCode(401)
            .body("error", equalTo("second_step_expired"));
        totpStep(null, "123456").then().statusCode(401).body("error", equalTo("second_step_expired"));
    }

    @Test
    void wrongCodeKeepsTheTokenForARetry() throws Exception {
        var half = passwordStep();
        totpStep(half, "000000x").then().statusCode(401).body("error", equalTo("invalid_code"));
        totpStep(half, TwoFactorResourceQuarkusTest.code(secret, clock.instant())).then().statusCode(204);
    }

    @Test
    void wrongCodesHitTheLimiter() throws Exception {
        var half = passwordStep();
        for (int i = 0; i < 5; i++) {
            totpStep(half, "abcdef").then().statusCode(401).body("error", equalTo("invalid_code"));
        }
        totpStep(half, TwoFactorResourceQuarkusTest.code(secret, clock.instant()))
            .then()
            .statusCode(429)
            .body("error", equalTo("rate_limited"));
        login("admin", "correct horse battery").then().statusCode(429);
    }

    @Test
    void userWithoutFactorsStill204() {
        var plain = users.create("plain", "p@example.org", "Plain", Role.ADMINISTRATOR).id();
        users.setPassword(plain, TestStore.PASSWORD_HASH);
        var r = login("plain", "correct horse battery").then().statusCode(204).extract();
        assertThat(r.cookies()).containsKey("sp_session").doesNotContainKey("sp_2fa");
    }
}
