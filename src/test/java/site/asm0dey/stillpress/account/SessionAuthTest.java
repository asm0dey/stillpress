package site.asm0dey.stillpress.account;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.SESSION;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.Csrf;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
class SessionAuthTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    DSLContext db;
    @TestHTTPResource("/")
    URL app;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void validSessionAuthenticates() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raw = sessions.create(u.getId());
        given().cookie("sp_session", raw).get("/admin/api/test/whoami").then().statusCode(200).body(equalTo("admin"));
    }

    @Test
    void serverStoresOnlyTheHash() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raw = sessions.create(u.getId());
        assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
        assertThat(db.fetchValue(SESSION.ID)).hasSize(64).isNotEqualTo(raw);
    }

    @Test
    void noOrUnknownCookieIs401() {
        given().get("/admin/api/test/whoami").then().statusCode(401).header("WWW-Authenticate", (String) null);
        given().cookie("sp_session", "nope").get("/admin/api/test/whoami").then().statusCode(401);
    }

    @Test
    void wrongRoleIs403() {
        var u = users.create("writer", "w@example.org", "W", Role.AUTHOR);
        given().cookie("sp_session", sessions.create(u.getId())).get("/admin/api/test/whoami").then().statusCode(403);
    }

    @Test
    void expiredSessionIs401AndSweptOnCreate() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raw = "expired-session-token";
        var now = LocalDateTime.now(ZoneOffset.UTC);
        db
            .insertInto(SESSION, SESSION.ID, SESSION.USER_ID, SESSION.CREATED_AT, SESSION.EXPIRES_AT)
            .values(SessionRepository.hash(raw), u.getId(), now.minusDays(15), now.minusDays(1))
            .execute();
        given().cookie("sp_session", raw).get("/admin/api/test/whoami").then().statusCode(401);
        // the next login sweeps expired rows
        sessions.create(u.getId());
        assertThat(db.fetchCount(SESSION)).isOne();
    }

    @Test
    void deletedSessionIs401() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raw = sessions.create(u.getId());
        sessions.delete(raw);
        given().cookie("sp_session", raw).get("/admin/api/test/whoami").then().statusCode(401);
    }

    @Test
    void stateChangingCallWithoutValidCsrfTokenIs400() {
        var token = Csrf.issue();
        given().post("/admin/api/logout").then().statusCode(400);
        given().header("X-CSRF-TOKEN", token.header()).post("/admin/api/logout").then().statusCode(400);
        given()
            .cookie("csrf-token", token.cookie())
            .contentType(JSON)
            .body("{}")
            .post("/admin/api/login")
            .then()
            .statusCode(400);
        given()
            .cookie("csrf-token", token.cookie())
            .header("X-CSRF-TOKEN", "d3JvbmctdG9rZW4tLS0tLQ")
            .post("/admin/api/logout")
            .then()
            .statusCode(400);
        // the signed cookie echoed as the header, which is what an unsigned double-submit would accept
        given()
            .cookie("csrf-token", token.cookie())
            .header("X-CSRF-TOKEN", token.cookie())
            .post("/admin/api/logout")
            .then()
            .statusCode(400);
    }

    @Test
    void plantedUnsignedCookieIsRejected() {
        // a value a sibling subdomain could plant, sized like a signature so only the HMAC check can refuse it
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        var planted = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        given()
            .cookie("csrf-token", planted)
            .header("X-CSRF-TOKEN", planted)
            .post("/admin/api/logout")
            .then()
            .statusCode(400);
        given()
            .cookie("csrf-token", "planted")
            .header("X-CSRF-TOKEN", "planted")
            .post("/admin/api/logout")
            .then()
            .statusCode(400);
    }

    @Test
    void csrfGuardSeesThePathTheRouterSees() throws IOException {
        // a raw socket, because HTTP clients normalise these paths before sending them
        var issued = Csrf.issue();
        var token = "Cookie: csrf-token=" + issued.cookie() + "\r\nX-CSRF-TOKEN: " + issued.header() + "\r\n";
        for (var path : List.of("/admin//api/logout", "/admin/%61pi/logout", "/admin/x/../api/logout")) {
            assertThat(rawPostStatus(path, "")).as(path + " without a token").isEqualTo(400);
            // the same path with a token reaches logout, so the 400 above is the CSRF guard, not path rejection
            assertThat(rawPostStatus(path, token)).as(path + " with a token").isEqualTo(204);
        }
    }

    private int rawPostStatus(String path, String headers) throws IOException {
        try (var s = new Socket(app.getHost(), app.getPort())) {
            s.setSoTimeout(5000);
            s
                .getOutputStream()
                .write(("POST "
                        + path
                        + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n"
                        + headers
                        + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII)
                );
            var line =
                    new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    @Test
    void csrfTokenFromAnAnonymousGetPassesTheGuard() {
        var response = given().get("/admin/api/setup").then().statusCode(200).extract();
        var cookie = response.detailedCookie("csrf-token");
        // the admin UI takes the raw token from the header; the cookie holds only its signature
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(Base64.getUrlDecoder().decode(cookie.getValue())).hasSize(32);
        assertThat(response.header("X-CSRF-TOKEN")).isNotEqualTo(cookie.getValue());
        given()
            .cookie("csrf-token", cookie.getValue())
            .header("X-CSRF-TOKEN", response.header("X-CSRF-TOKEN"))
            .post("/admin/api/logout")
            .then()
            .statusCode(204);
        csrf().post("/admin/api/logout").then().statusCode(204);
    }

    @Test
    void sessionCookieAttributes() {
        // Vert.x spells the attribute "HTTPOnly"; attribute names are case-insensitive
        assertThat(SessionAuthMechanism.cookie("abc", false).encode().toLowerCase(Locale.ROOT))
            .contains("sp_session=abc", "path=/", "httponly", "samesite=lax", "max-age=1209600")
            .doesNotContain("secure");
        assertThat(SessionAuthMechanism.cookie("abc", true).encode()).containsIgnoringCase("secure");
        assertThat(SessionAuthMechanism.clearedCookie(false).encode()).contains("sp_session=", "Max-Age=0");
    }
}
