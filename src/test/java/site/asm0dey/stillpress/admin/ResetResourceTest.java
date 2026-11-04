package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ResetResourceTest {
    private static final String PASSWORD = "correct horse battery";
    // own client addresses, so these failures never fill the shared IP bucket other tests log in from
    private static final AtomicInteger IP = new AtomicInteger(1);
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    ImportGuard guard;
    @Inject
    SiteReset reset;
    @Inject
    SetupToken token;
    long adminId;
    String adminCookie;
    String editorCookie;
    String ip;

    @BeforeEach
    void clean() {
        store.reset();
        settings.put("title", "My Site");
        adminId = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).getId();
        users.setPassword(adminId, Passwords.hash(PASSWORD));
        var editorId = users.create("editor", "e@example.org", "Editor", Role.EDITOR).getId();
        users.setPassword(editorId, Passwords.hash(PASSWORD));
        adminCookie = sessions.create(adminId);
        editorCookie = sessions.create(editorId);
        ip = "198.51.100." + IP.getAndIncrement();
    }

    @Test
    void wrongPasswordIs403AndNothingDeleted() {
        reset(adminCookie, "wrong password!", "My Site").then().statusCode(403).body(
                "error",
                equalTo("invalid_credentials")
        );
        assertThat(users.all()).hasSize(2);
    }

    @Test
    void fifthWrongPasswordLocksOut() {
        for (var i = 0; i < 5; i++) {
            reset(adminCookie, "wrong password!", "My Site").then().statusCode(403);
        }
        reset(adminCookie, PASSWORD, "My Site").then().statusCode(429).body("error", equalTo("rate_limited"));
        assertThat(users.all()).hasSize(2);
    }

    @Test
    void wrongConfirmIs400() {
        reset(adminCookie, PASSWORD, "my site")
            .then()
            .statusCode(400)
            .body("error", equalTo("invalid"))
            .body("field", equalTo("confirm"));
        assertThat(users.all()).hasSize(2);
    }

    @Test
    void refusedWhileImportRuns() throws Exception {
        guard.begin();
        reset(adminCookie, PASSWORD, "My Site").then().statusCode(409).body("error", equalTo("import_running"));
        assertThat(users.all()).hasSize(2);
    }

    @Test
    void otherUsersAreLoggedOutCallerIsNot() {
        var otherAdminSession = sessions.create(adminId);
        reset(adminCookie, PASSWORD, "My Site").then().statusCode(204);
        csrf().cookie("sp_session", editorCookie).get("/admin/api/session").then().statusCode(401);
        csrf().cookie("sp_session", otherAdminSession).get("/admin/api/session").then().statusCode(401);
        csrf()
            .cookie("sp_session", adminCookie)
            .get("/admin/api/session")
            .then()
            .statusCode(200)
            .body("login", equalTo("admin"));
        csrf()
            .cookie("sp_session", adminCookie)
            .get("/admin/api/site")
            .then()
            .statusCode(200)
            .body("importAllowed", equalTo(true));
        assertThat(users.all())
            .extracting(u -> u.getLogin())
            .containsExactly("admin");
    }

    @Test
    void editorIs403() {
        reset(editorCookie, PASSWORD, "My Site").then().statusCode(403);
        assertThat(users.all()).hasSize(2);
    }

    @Test
    void wipePrintsASetupTokenOnTheNextStart() {
        token.discard();
        reset.wipe();
        token.onStart(null);
        assertThat(users.all()).isEmpty();
        assertThat(token.current()).isPresent();
    }

    private Response reset(String cookie, String password, String confirm) {
        return csrf()
            .cookie("sp_session", cookie)
            .header("X-Forwarded-For", ip)
            .contentType(JSON)
            .body(Map.of("password", password, "confirm", confirm))
            .post("/admin/api/reset");
    }
}
