package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static site.asm0dey.stillpress.Csrf.csrf;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import module java.base;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class TwoFactorResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    TwoFactorRepository factors;
    @Inject
    Clock clock;
    String session;
    long user;

    @BeforeEach
    void login() {
        store.reset();
        user = users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).id();
        session = sessions.create(user);
    }

    io.restassured.specification.RequestSpecification asAdmin() {
        return csrf().cookie("sp_session", session);
    }

    /**
     * The code an authenticator would show for the Base32 secret on the app clock (tests move it).
     */
    String codeFor(String secret) throws Exception {
        return code(secret, clock.instant());
    }

    /**
     * The code an authenticator would show for the Base32 secret at {@code at}.
     */
    static String code(String secret, Instant at) throws Exception {
        var seed = new byte[secret.length() * 5 / 8];
        var bits = 0;
        var acc = 0;
        var n = 0;
        for (char c : secret.toCharArray()) {
            acc = acc << 5 | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            bits += 5;
            if (bits >= 8) {
                seed[n++] = (byte) (acc >> (bits - 8));
                bits -= 8;
            }
        }
        return new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1")
            .generateOneTimePasswordString(new SecretKeySpec(seed, "HmacSHA1"), at);
    }

    @Test
    void enrollConfirmAndList() throws Exception {
        var secret = asAdmin()
            .post("/admin/api/2fa/totp")
            .then()
            .statusCode(200)
            .body("secret", org.hamcrest.Matchers.matchesPattern("[A-Z2-7]{32}"))
            .body("uri", startsWith("otpauth://totp/"))
            .body("qrSvg", startsWith("<svg"))
            .extract()
            .path("secret")
            .toString();
        asAdmin().get("/admin/api/2fa").then().statusCode(200).body("totp", equalTo(false));
        asAdmin()
            .contentType(JSON)
            .body(Map.of("code", codeFor(secret)))
            .post("/admin/api/2fa/totp/confirm")
            .then()
            .statusCode(204);
        asAdmin().get("/admin/api/2fa").then().statusCode(200).body("totp", equalTo(true)).body(
                "required",
                equalTo(false)
        );
        assertThat(factors.totp(user)).hasValueSatisfying(t -> assertThat(t.ciphertext()).doesNotContain(secret));
    }

    @Test
    void wrongCodeIs400() {
        asAdmin().post("/admin/api/2fa/totp").then().statusCode(200);
        asAdmin()
            .contentType(JSON)
            .body(Map.of("code", "000000x"))
            .post("/admin/api/2fa/totp/confirm")
            .then()
            .statusCode(400)
            .body("error", equalTo("invalid_code"));
    }

    @Test
    void secondEnrollmentIs409() throws Exception {
        var secret = asAdmin().post("/admin/api/2fa/totp").then().extract().path("secret").toString();
        asAdmin()
            .contentType(JSON)
            .body(Map.of("code", codeFor(secret)))
            .post("/admin/api/2fa/totp/confirm")
            .then()
            .statusCode(204);
        asAdmin().post("/admin/api/2fa/totp").then().statusCode(409).body("error", equalTo("totp_exists"));
    }

    @Test
    void deleteRemovesTotpAnd404sWhenNone() {
        asAdmin().delete("/admin/api/2fa/totp").then().statusCode(404);
        asAdmin().post("/admin/api/2fa/totp").then().statusCode(200);
        asAdmin().delete("/admin/api/2fa/totp").then().statusCode(204);
        assertThat(factors.totp(user)).isEmpty();
    }

    @Test
    void enrollingAfterConfirmKeepsTheFactor() throws Exception {
        var secret = asAdmin().post("/admin/api/2fa/totp").then().extract().path("secret").toString();
        asAdmin().contentType(JSON).body(Map.of("code", codeFor(secret))).post("/admin/api/2fa/totp/confirm");
        var stored = factors.totp(user).orElseThrow().ciphertext();
        asAdmin().post("/admin/api/2fa/totp").then().statusCode(409);
        assertThat(factors.totp(user)).hasValueSatisfying(t -> {
            assertThat(t.confirmed()).isTrue();
            assertThat(t.ciphertext()).isEqualTo(stored);
        });
    }

    @Test
    void anonymousIs401() {
        RestAssured.get("/admin/api/2fa").then().statusCode(401);
        csrf().post("/admin/api/2fa/totp").then().statusCode(401);
    }
}
