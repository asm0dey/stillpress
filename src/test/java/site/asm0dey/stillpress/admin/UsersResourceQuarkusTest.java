package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class UsersResourceQuarkusTest {
    // one server per class on GreenMail's fixed test port; test classes run one after another, so they never share it
    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);
    @Inject
    SiteMail mail;
    @Inject
    DSLContext db;
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
    @Inject
    TwoFactorRepository factors;
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
        mail.reload();
    }

    @Test
    void adminClearsAUsersFactors() {
        enroll(writerId);
        assertThat(factors.factorCount(writerId)).isEqualTo(2);
        as(adminCookie).delete("/admin/api/users/" + writerId + "/two-factor").then().statusCode(204);
        assertThat(factors.hasFactor(writerId)).isFalse();
        csrf().cookie("sp_session", writerCookie).get("/admin/api/session").then().statusCode(401);
        as(adminCookie).get("/admin/api/users").then().body("find { it.login == 'writer' }.twoFactor", equalTo(false));
        as(adminCookie).delete("/admin/api/users/999999/two-factor").then().statusCode(404);
        as(sessions.create(writerId)).delete("/admin/api/users/" + adminId + "/two-factor").then().statusCode(403);
    }

    @Test
    void listShowsTwoFactor() {
        as(adminCookie).get("/admin/api/users").then().body("find { it.login == 'writer' }.twoFactor", equalTo(false));
        factors.putTotp(writerId, "seed");
        // an unconfirmed authenticator is no factor
        as(adminCookie).get("/admin/api/users").then().body("find { it.login == 'writer' }.twoFactor", equalTo(false));
        factors.confirmTotp(writerId, "seed", 1);
        as(adminCookie)
            .get("/admin/api/users")
            .then()
            .body("find { it.login == 'writer' }.twoFactor", equalTo(true))
            .body("find { it.login == 'admin' }.twoFactor", equalTo(false));
    }

    private void enroll(long id) {
        factors.putTotp(id, "seed");
        factors.confirmTotp(id, "seed", 1);
        factors.addPasskey(
                id,
                "key",
                new io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData(
                        "u" + id,
                        "cred-" + id,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new byte[] {1, 2, 3, 4},
                        -7,
                        0
                )
        );
    }

    @Test
    void createMailsInviteWhenMailConfigured() throws Exception {
        settings.put("admin_url", "https://blog.example.org");
        smtp(ServerSetupTest.SMTP.getPort());
        var id = create("editor1", "editor")
            .then()
            .statusCode(201)
            .body("mailed", equalTo(true))
            .body("$", not(hasKey("setPasswordUrl")))
            .extract()
            .<Integer>path("id");
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        var received = SMTP.getReceivedMessages();
        assertThat(received[0].getAllRecipients()).extracting(Object::toString).containsExactly("editor1@example.org");
        var body = received[0].getContent().toString();
        assertThat(body).contains("https://blog.example.org/admin/set-password?token=");
        var expires = db
            .select(USER_TOKEN.EXPIRES_AT)
            .from(USER_TOKEN)
            .where(USER_TOKEN.USER_ID.eq((long) id))
            .fetchSingle(USER_TOKEN.EXPIRES_AT);
        var week = LocalDateTime.now(ZoneOffset.UTC).plusDays(7);
        assertThat(expires).isBetween(week.minusMinutes(1), week.plusMinutes(1));
        setPassword(token(body), "editor password 1").then().statusCode(204);
    }

    @Test
    void createWithoutMailReturnsLink() {
        create("editor1", "editor")
            .then()
            .statusCode(201)
            .body("mailed", equalTo(false))
            .body("setPasswordUrl", startsWith("/admin/set-password?token="));
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void inviteMailFailureKeepsTheUser() throws IOException {
        try (var closed = new ServerSocket(0)) {
            smtp(closed.getLocalPort());
        }
        create("editor1", "editor").then().statusCode(201).body("mailed", equalTo(true));
        assertThat(users.findByLogin("editor1")).isPresent();
        TestStore.await(() -> settings.get(SiteMail.LAST_ERROR).isPresent(), 10);
        assertThat(settings.get(SiteMail.LAST_ERROR)).isNotEmpty();
    }

    @Test
    void inviteRevokesEarlierInviteButNotACopiedLink() throws Exception {
        var pending = users.create("pending", "pending@example.org", "P", Role.AUTHOR).id();
        var copied = asAdmin()
            .post("/admin/api/users/" + pending + "/password-link")
            .then()
            .statusCode(200)
            .extract()
            .<String>path("url");
        smtp(ServerSetupTest.SMTP.getPort());
        asAdmin().post("/admin/api/users/" + pending + "/invite").then().statusCode(204);
        asAdmin().post("/admin/api/users/" + pending + "/invite").then().statusCode(204);
        assertThat(SMTP.waitForIncomingEmail(5000, 2)).isTrue();
        var first = token(SMTP.getReceivedMessages()[0].getContent().toString());
        var second = token(SMTP.getReceivedMessages()[1].getContent().toString());
        setPassword(first, "pending password 1").then().statusCode(410);
        setPassword(second, "pending password 1").then().statusCode(204);
        // spending the invite kills the copied link too
        setPassword(copied.substring(copied.indexOf("token=") + 6), "pending password 1").then().statusCode(410);
    }

    private void smtp(int port) {
        settings.put("smtp_host", "localhost");
        settings.put("smtp_port", String.valueOf(port));
        settings.put("smtp_security", "none");
        settings.put("smtp_from", "blog@example.org");
        mail.reload();
    }

    /**
     * The token of the first set-password link in a mail body.
     */
    private static String token(String body) {
        assertThat(body).containsPattern("token=\\S+");
        return body.replaceFirst("(?s).*?token=(\\S+).*", "$1");
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
    void createReturnsWorkingSetPasswordLink() {
        var url = create("editor1", "editor").then().statusCode(201).extract().<String>path("setPasswordUrl");
        assertThat(url).startsWith("/admin/set-password?token=");
        var token = url.substring(url.indexOf("token=") + 6);
        setPassword(token, "editor password 1").then().statusCode(204);
        login("editor1", "editor password 1").then().statusCode(204);
    }

    @Test
    void linkUsesAdminUrlWhenSet() {
        settings.put("admin_url", "https://blog.example.org/");
        create("editor1", "editor")
            .then()
            .statusCode(201)
            .body("setPasswordUrl", startsWith("https://blog.example.org/admin/set-password?token="));
    }

    @Test
    void concurrentSameLoginGivesOne201AndOtherwise409() {
        var codes = IntStream
            .range(0, 8)
            .parallel()
            .mapToObj(i -> asAdmin()
                .body(Map.of("login", "dup", "email", "dup" + i + "@example.org", "displayName", "D", "role", "author"))
                .post("/admin/api/users")
                .statusCode())
            .toList();
        assertThat(codes).containsOnlyOnce(201).containsOnly(201, 409);
    }

    @Test
    void concurrentCreatesSameEmailDifferentCase() {
        var emails = List.of("A@x.org", "a@x.org");
        var codes = IntStream
            .range(0, 2)
            .parallel()
            .mapToObj(i -> asAdmin()
                .body(Map.of("login", "mail" + i, "email", emails.get(i), "displayName", "D", "role", "author"))
                .post("/admin/api/users"))
            .toList();
        assertThat(codes.stream().map(Response::statusCode).sorted()).containsExactly(201, 409);
        assertThat(codes
            .stream()
            .filter(r -> r.statusCode() == 409)
            .map(r -> r.jsonPath().getString("error")))
            .containsExactly("email_taken");
    }

    @Test
    void profileUpdateRaceMapsTo409() {
        // each updater passes or fails the up-front check; the unique index settles whoever is left
        var other = user("other", Role.AUTHOR);
        var ids = List.of(writerId, other);
        var codes = IntStream
            .range(0, 2)
            .parallel()
            .mapToObj(i -> asAdmin()
                .body(Map.of("displayName", "P", "email", i == 0 ? "Same@x.org" : "same@x.org"))
                .put("/admin/api/users/" + ids.get(i))
                .statusCode())
            .sorted()
            .toList();
        assertThat(codes).containsExactly(204, 409);
    }

    @Test
    void lastAdministratorCannotBeDemoted() {
        asAdmin()
            .body(Map.of("role", "editor"))
            .put("/admin/api/users/" + adminId + "/role")
            .then()
            .statusCode(409)
            .body("error", equalTo("last_administrator"));
        assertThat(users.findById(adminId).orElseThrow().role()).isEqualTo("administrator");
    }

    @Test
    void refusedDemotionLeavesImportOpen() {
        asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/" + adminId + "/role").then().statusCode(409);
        asAdmin().get("/admin/api/site").then().statusCode(200).body("importAllowed", equalTo(true));
    }

    @Test
    void administratorWithoutPasswordDoesNotCountAsAnotherOne() {
        var other = users.create("pending", "p@example.org", "P", Role.ADMINISTRATOR).id();
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
        assertThat(users.findById(adminId).orElseThrow().role()).isEqualTo("editor");
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
        assertThat(u.displayName()).isEqualTo("Wendy Writer");
        assertThat(u.email()).isEqualTo("wendy@example.org");
        assertThat(u.slug()).isEqualTo("writer");
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
        assertThat(users.findById(adminId).orElseThrow().displayName()).isEqualTo("admin");
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
        var id = users.create(login, login + "@example.org", login, role).id();
        users.setPassword(id, TestStore.PASSWORD_HASH);
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

    @Test
    void anonymousIs401() {
        var id = "/admin/api/users/" + writerId;
        for (var call :
                List.of(
                        List.of("GET", "/admin/api/users"),
                        List.of("POST", "/admin/api/users"),
                        List.of("DELETE", id + "/two-factor"),
                        List.of("POST", id + "/invite"),
                        List.of("PUT", id),
                        List.of("PUT", id + "/role"),
                        List.of("POST", id + "/password-link")
        )) {
            AuthMatrix.anonymousIs401(call.get(0), call.get(1));
        }
    }

    @Test
    void onlyAnAdministratorMayUseTheAdminEndpoints() {
        var id = "/admin/api/users/" + adminId;
        AuthMatrix.forbiddenFor(writerCookie, "GET", "/admin/api/users");
        AuthMatrix.forbiddenFor(writerCookie, "POST", "/admin/api/users");
        AuthMatrix.forbiddenFor(writerCookie, "DELETE", id + "/two-factor");
        AuthMatrix.forbiddenFor(writerCookie, "POST", id + "/invite");
        AuthMatrix.forbiddenFor(writerCookie, "PUT", id + "/role", "{\"role\":\"editor\"}");
        AuthMatrix.forbiddenFor(writerCookie, "POST", id + "/password-link");
        assertThat(users.findById(writerId).orElseThrow().role()).isEqualTo("author");
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        var id = "/admin/api/users/" + writerId;
        AuthMatrix.missingCsrfIs400(adminCookie, "POST", "/admin/api/users");
        AuthMatrix.missingCsrfIs400(adminCookie, "DELETE", id + "/two-factor");
        AuthMatrix.missingCsrfIs400(adminCookie, "POST", id + "/invite");
        AuthMatrix.missingCsrfIs400(adminCookie, "PUT", id);
        AuthMatrix.missingCsrfIs400(adminCookie, "PUT", id + "/role");
        AuthMatrix.missingCsrfIs400(adminCookie, "POST", id + "/password-link");
    }
}
