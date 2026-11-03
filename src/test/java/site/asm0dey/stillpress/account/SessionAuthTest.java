package site.asm0dey.stillpress.account;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static site.asm0dey.stillpress.db.Tables.SESSION;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void postWithoutCsrfHeaderIs403() {
        given().post("/admin/api/logout").then().statusCode(403).body(equalTo("Missing X-Requested-With"));
        given().header("X-Requested-With", "XMLHttpRequest").delete("/admin/api/logout").then().statusCode(403);
    }

    @Test
    void csrfGuardSeesThePathTheRouterSees() {
        for (var path : List.of("/admin//api/logout", "/admin/%61pi/logout", "/admin/x/../api/logout")) {
            given().urlEncodingEnabled(false).post(path).then().statusCode(403);
        }
    }

    @Test
    void csrfHeaderPassesTheFilter() {
        given()
            .header("X-Requested-With", "stillpress")
            .post("/admin/api/logout")
            .then()
            .statusCode(not(403))
            .body(not(containsString("Missing X-Requested-With")));
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
