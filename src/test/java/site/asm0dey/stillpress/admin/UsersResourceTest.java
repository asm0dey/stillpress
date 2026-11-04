package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class UsersResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    DataDir dataDir;
    long adminId;
    long writerId;
    String adminCookie;
    String writerCookie;

    @BeforeEach
    void reset() {
        store.reset();
        adminId = user("admin", Role.ADMINISTRATOR);
        writerId = user("writer", Role.AUTHOR);
        adminCookie = sessions.create(adminId);
        writerCookie = sessions.create(writerId);
    }

    @Test
    void administratorListsUsers() {
        asAdmin()
            .get("/admin/api/users")
            .then()
            .statusCode(200)
            .body("login", contains("admin", "writer"))
            .body("role", contains("administrator", "author"))
            .body("hasPassword", contains(true, true));
    }

    @Test
    void nonAdministratorIs403() {
        as(writerCookie).get("/admin/api/users").then().statusCode(403);
        as(writerCookie).body(Map.of("role", "editor")).put("/admin/api/users/" + writerId + "/role").then().statusCode(
                403
        );
    }

    @Test
    void createReturnsWorkingSetPasswordLink() {
        var url = create("editor1", "editor").then().statusCode(201).extract().<String>path("setPasswordUrl");
        assertThat(url).startsWith("/admin/set-password?token=");
        var token = url.substring(url.indexOf("token=") + 6);
        setPassword(token, "editor password 1").then().statusCode(204);
        login("editor1", "editor password 1").then().statusCode(204);
    }

    @Test
    void linkUsesHomeUrlWhenSet() {
        settings.put("home_url", "https://blog.example.org/");
        create("editor1", "editor")
            .then()
            .statusCode(201)
            .body("setPasswordUrl", startsWith("https://blog.example.org/admin/set-password?token="));
    }

    @Test
    void duplicateLoginIs409() {
        create("writer", "author").then().statusCode(409).body("error", equalTo("login_taken"));
    }

    @Test
    void unknownRoleIs400() {
        create("editor1", "king").then().statusCode(400);
    }

    @Test
    void lastAdministratorCannotBeDemoted() {
        asAdmin()
            .body(Map.of("role", "editor"))
            .put("/admin/api/users/" + adminId + "/role")
            .then()
            .statusCode(409)
            .body("error", equalTo("last_administrator"));
        assertThat(users.findById(adminId).orElseThrow().getRole()).isEqualTo("administrator");
    }

    @Test
    void refusedDemotionLeavesImportOpen() {
        asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/" + adminId + "/role").then().statusCode(409);
        asAdmin().get("/admin/api/site").then().statusCode(200).body("importAllowed", equalTo(true));
    }

    @Test
    void administratorWithoutPasswordDoesNotCountAsAnotherOne() {
        var other = users.create("pending", "p@example.org", "P", Role.ADMINISTRATOR).getId();
        asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/" + adminId + "/role").then().statusCode(409);
        // demoting the passwordless one is fine
        asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/" + other + "/role").then().statusCode(204);
    }

    @Test
    void demotionAllowedWhenAnotherAdministratorExists() {
        asAdmin().body(Map.of("role", "administrator")).put("/admin/api/users/" + writerId + "/role").then().statusCode(
                204
        );
        asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/" + adminId + "/role").then().statusCode(204);
        assertThat(users.findById(adminId).orElseThrow().getRole()).isEqualTo("editor");
    }

    @Test
    void changingRoleOfUnknownUserIs404AndUnknownRoleIs400() {
        asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/999999/role").then().statusCode(404);
        asAdmin().body(Map.of("role", "king")).put("/admin/api/users/" + writerId + "/role").then().statusCode(400);
    }

    @Test
    void passwordLinkIsIssuedForAnExistingUser() {
        var url = asAdmin()
            .post("/admin/api/users/" + writerId + "/password-link")
            .then()
            .statusCode(200)
            .extract()
            .<String>path("url");
        setPassword(url.substring(url.indexOf("token=") + 6), "another password 1").then().statusCode(204);
        asAdmin().post("/admin/api/users/999999/password-link").then().statusCode(404);
    }

    @Test
    void userEditsOwnDisplayNameAndArchiveHeadingChanges() {
        settings.put("title", "Blog");
        as(writerCookie)
            .body(Map.of("displayName", "  Wendy Writer ", "email", "wendy@example.org"))
            .put("/admin/api/users/" + writerId)
            .then()
            .statusCode(204);
        var u = users.findById(writerId).orElseThrow();
        assertThat(u.getDisplayName()).isEqualTo("Wendy Writer");
        assertThat(u.getEmail()).isEqualTo("wendy@example.org");
        assertThat(u.getSlug()).isEqualTo("writer");
        var archive = dataDir.current().resolve("author/writer/index.html");
        TestStore.await(() -> {
            try {
                return Files.exists(archive) && Files.readString(archive).contains("Wendy Writer");
            } catch (IOException e) {
                return false;
            }
        }, 30);
        assertThat(archive).content().contains("Wendy Writer");
    }

    @Test
    void authorEditingSomeoneElseIs403() {
        as(writerCookie)
            .body(Map.of("displayName", "Hacked", "email", "admin@example.org"))
            .put("/admin/api/users/" + adminId)
            .then()
            .statusCode(403);
        assertThat(users.findById(adminId).orElseThrow().getDisplayName()).isEqualTo("admin");
    }

    @Test
    void administratorEditsAnyoneAndEmailMustBeFree() {
        asAdmin()
            .body(Map.of("displayName", "W", "email", "w2@example.org"))
            .put("/admin/api/users/" + writerId)
            .then()
            .statusCode(204);
        as(writerCookie)
            .body(Map.of("displayName", "W", "email", "ADMIN@example.org"))
            .put("/admin/api/users/" + writerId)
            .then()
            .statusCode(409)
            .body("error", equalTo("email_taken"));
        asAdmin()
            .body(Map.of("displayName", "W", "email", "x@example.org"))
            .put("/admin/api/users/999999")
            .then()
            .statusCode(404);
    }

    @Test
    void invalidProfileFieldsAre400AndLeaveImportOpen() {
        asAdmin()
            .body(Map.of("displayName", "x".repeat(251), "email", "w@example.org"))
            .put("/admin/api/users/" + writerId)
            .then()
            .statusCode(400)
            .body("field", equalTo("displayName"));
        asAdmin()
            .body(Map.of("displayName", "W", "email", "x".repeat(95) + "@e.org"))
            .put("/admin/api/users/" + writerId)
            .then()
            .statusCode(400)
            .body("field", equalTo("email"));
        assertThat(settings.get("import_state")).isNotPresent();
    }

    private long user(String login, Role role) {
        var id = users.create(login, login + "@example.org", login, role).getId();
        users.setPassword(id, Passwords.hash("correct horse battery"));
        return id;
    }

    private RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }

    private RequestSpecification asAdmin() {
        return as(adminCookie);
    }

    private Response create(String login, String role) {
        return asAdmin()
            .body(Map.of("login", login, "email", login + "@example.org", "displayName", "E", "role", role))
            .post("/admin/api/users");
    }

    private static Response setPassword(String token, String password) {
        return csrf()
            .contentType(JSON)
            .body(Map.of("token", token, "password", password))
            .post("/admin/api/set-password");
    }

    private static Response login(String login, String password) {
        return csrf().contentType(JSON).body(Map.of("login", login, "password", password)).post("/admin/api/login");
    }
}
