package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Limiters;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SetPasswordLink;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class PasswordResetTest {
    // one server per class on GreenMail's fixed test port; test classes run one after another, so they never share it
    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    SiteMail mail;
    @Inject
    DSLContext db;
    @Inject
    SetPasswordLink links;
    long adaId;

    @BeforeEach
    void reset() {
        store.reset();
        Limiters.reset();
        settings.put("title", "Blog");
        settings.put("admin_url", "https://blog.example.org");
        settings.put("smtp_host", "localhost");
        settings.put("smtp_port", String.valueOf(ServerSetupTest.SMTP.getPort()));
        settings.put("smtp_security", "none");
        settings.put("smtp_from", "blog@example.org");
        mail.reload();
        adaId = users.create("ada", "ada@example.org", "Ada", Role.AUTHOR).getId();
        users.setPassword(adaId, TestStore.PASSWORD_HASH);
    }

    @Test
    void unknownUserAndKnownUserSameAnswer() throws Exception {
        var unknown = reset("nobody");
        var known = reset("ada");
        assertThat(List.of(unknown.statusCode(), known.statusCode())).containsExactly(204, 204);
        assertThat(known.asString()).isEqualTo(unknown.asString());
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> m.getAllRecipients()[0].toString())
            .containsExactly("ada@example.org");
    }

    @Test
    void resetLinkIgnoresRequestHost() throws Exception {
        // from localhost, a trusted proxy: the forwarded headers are honoured, yet the link still comes from admin_url
        csrf()
            .contentType(JSON)
            .header("Host", "evil.example")
            .header("X-Forwarded-Host", "evil.example")
            .body(Map.of("login", "ada@example.org"))
            .post("/admin/api/password-reset")
            .then()
            .statusCode(204);
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(SMTP.getReceivedMessages()[0].getContent().toString())
            .contains("https://blog.example.org/admin/set-password?token=")
            .doesNotContain("evil.example");
    }

    @Test
    void resetKeepsAPendingInvite() throws Exception {
        var invite = links.issue(adaId, TokenRepository.INVITE, SetPasswordLink.INVITE_TTL);
        reset("ada").then().statusCode(204);
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        var resetMail = SMTP.getReceivedMessages()[0].getContent().toString();
        assertThat(db.fetchCount(USER_TOKEN, USER_TOKEN.USER_ID.eq(adaId))).isEqualTo(2);
        setPassword(invite.substring(invite.indexOf("token=") + 6)).then().statusCode(204);
        setPassword(resetMail.replaceFirst("(?s).*?token=(\\S+).*", "$1")).then().statusCode(410);
    }

    @Test
    void oneMailPerFiveMinutes() {
        reset("ada").then().statusCode(204);
        reset("ada").then().statusCode(204);
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(SMTP.waitForIncomingEmail(1000, 2)).isFalse();
        // SessionRepository's clock is not injectable: the earlier link is made older instead
        db.update(USER_TOKEN).set(USER_TOKEN.CREATED_AT, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(6)).execute();
        reset("ada").then().statusCode(204);
        assertThat(SMTP.waitForIncomingEmail(5000, 2)).isTrue();
        assertThat(SMTP.getReceivedMessages()).hasSize(2);
    }

    @Test
    void resetTokenExpiresInAnHour() {
        reset("ada").then().statusCode(204);
        var expires = db
            .select(USER_TOKEN.EXPIRES_AT)
            .from(USER_TOKEN)
            .where(USER_TOKEN.USER_ID.eq(adaId))
            .fetchSingle(USER_TOKEN.EXPIRES_AT);
        var hour = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        assertThat(expires).isBetween(hour.minusMinutes(1), hour.plusMinutes(1));
    }

    @Test
    void resetsCountAgainstTheIp() {
        var codes = IntStream
            .range(0, 21)
            .mapToObj(i -> csrf()
                .contentType(JSON)
                .header("X-Forwarded-For", "203.0.113.9")
                .body(Map.of("login", "nobody" + i))
                .post("/admin/api/password-reset")
                .statusCode())
            .toList();
        assertThat(codes.subList(0, 20)).containsOnly(204);
        assertThat(codes.get(20)).isEqualTo(429);
    }

    @Test
    void withoutMailNothingIsSent() {
        settings.delete("smtp_host");
        mail.reload();
        reset("ada").then().statusCode(204);
        assertThat(SMTP.waitForIncomingEmail(1000, 1)).isFalse();
        assertThat(db.fetchCount(USER_TOKEN)).isZero();
    }

    @Test
    void overlongLoginIs204() {
        reset("a".repeat(101)).then().statusCode(204);
    }

    @Test
    void loginOptionsTellWhetherMailIsOn() {
        RestAssured.get("/admin/api/login-options").then().statusCode(200).body("mail", equalTo(true));
        settings.delete("smtp_host");
        RestAssured.get("/admin/api/login-options").then().statusCode(200).body("mail", equalTo(false));
    }

    private static Response setPassword(String token) {
        return csrf()
            .contentType(JSON)
            .body(Map.of("token", token, "password", "a long enough password"))
            .post("/admin/api/set-password");
    }

    private static Response reset(String login) {
        return csrf().contentType(JSON).body(Map.of("login", login)).post("/admin/api/password-reset");
    }
}
