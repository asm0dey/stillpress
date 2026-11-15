package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static site.asm0dey.stillpress.Csrf.csrf;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class MailResourceQuarkusTest {
    // one server per class on GreenMail's fixed test port; test classes run one after another, so they never share it
    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    Secrets secrets;
    String admin;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("title", "Blog");
        settings.put("language", "en");
        admin = cookie("admin", "admin@example.org", Role.ADMINISTRATOR);
    }

    @Test
    void passwordIsStoredEncryptedAndNeverReturned() {
        put(local("s3cret")).then().statusCode(200);
        as(admin)
            .get("/admin/api/mail")
            .then()
            .statusCode(200)
            .body("$", not(hasKey("password")))
            .body("host", equalTo("localhost"))
            .body("port", equalTo(ServerSetupTest.SMTP.getPort()))
            .body("security", equalTo("none"))
            .body("from", equalTo("blog@example.org"))
            .body("passwordSet", equalTo(true));
        assertThat(secrets.get("smtp_password")).contains("s3cret");
        assertThat(settings.get("smtp_password")).isEmpty();
    }

    @Test
    void nullKeepsEmptyClearsPassword() {
        put(local("s3cret")).then().statusCode(200);
        put(local(null)).then().statusCode(200).body("passwordSet", equalTo(true));
        assertThat(secrets.get("smtp_password")).contains("s3cret");
        put(local("")).then().statusCode(200).body("passwordSet", equalTo(false));
        assertThat(secrets.get("smtp_password")).isEmpty();
    }

    @Test
    void clearingHostDisablesMail() {
        var body = local("s3cret");
        body.put("username", "blog");
        put(body).then().statusCode(200);
        var off = new HashMap<String, Object>();
        off.put("host", "");
        put(off).then().statusCode(200).body("host", equalTo("")).body("passwordSet", equalTo(false));
        assertThat(Stream
            .of("smtp_host", "smtp_port", "smtp_security", "smtp_username", "smtp_from")
            .map(settings::get)
        )
            .containsOnly(Optional.empty());
        assertThat(secrets.get("smtp_password")).isEmpty();
        as(admin).post("/admin/api/mail/test").then().statusCode(409).body("error", equalTo("mail_not_configured"));
    }

    @Test
    void testMailArrives() throws Exception {
        put(local(null)).then().statusCode(200);
        settings.put("mail_last_error", "2026-11-14T00:00:00Z earlier failure");
        as(admin).post("/admin/api/mail/test").then().statusCode(204);
        var received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).extracting(Object::toString).containsExactly("admin@example.org");
        assertThat(received[0].getSubject()).isEqualTo("Test email from Blog");
        assertThat(settings.get("mail_last_error")).isEmpty();
    }

    @Test
    void testMailFailureIs502() throws IOException {
        var body = local(null);
        try (var socket = new ServerSocket(0)) {
            body.put("port", socket.getLocalPort());
        }
        put(body).then().statusCode(200);
        as(admin)
            .post("/admin/api/mail/test")
            .then()
            .statusCode(502)
            .body("error", equalTo("mail_failed"))
            .body("message", not(equalTo("")));
    }

    @Test
    void nonAdminIs403() {
        var editor = cookie("editor", "editor@example.org", Role.EDITOR);
        as(editor).get("/admin/api/mail").then().statusCode(403);
        as(editor).body(local(null)).put("/admin/api/mail").then().statusCode(403);
        as(editor).post("/admin/api/mail/test").then().statusCode(403);
    }

    private static Map<String, Object> local(String password) {
        var body = new HashMap<String, Object>();
        body.put("host", "localhost");
        body.put("port", ServerSetupTest.SMTP.getPort());
        body.put("security", "none");
        body.put("username", "");
        body.put("from", "blog@example.org");
        body.put("password", password);
        return body;
    }

    private io.restassured.response.Response put(Map<String, Object> body) {
        return as(admin).body(body).put("/admin/api/mail");
    }

    private String cookie(String login, String email, Role role) {
        var id = users.create(login, email, login, role).id();
        users.setPassword(id, TestStore.PASSWORD_HASH);
        return sessions.create(id);
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }

    @Test
    void anonymousIs401() {
        AuthMatrix.anonymousIs401("GET", "/admin/api/mail");
        AuthMatrix.anonymousIs401("PUT", "/admin/api/mail");
        AuthMatrix.anonymousIs401("POST", "/admin/api/mail/test");
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        AuthMatrix.missingCsrfIs400(admin, "PUT", "/admin/api/mail");
        AuthMatrix.missingCsrfIs400(admin, "POST", "/admin/api/mail/test");
    }
}
