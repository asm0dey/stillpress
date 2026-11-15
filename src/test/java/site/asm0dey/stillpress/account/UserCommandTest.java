package site.asm0dey.stillpress.account;

import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class UserCommandTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    TokenRepository tokens;
    @Inject
    TwoFactorRepository factors;
    long adminId;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @BeforeEach
    void reset() {
        store.reset();
        adminId = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).getId();
    }

    @Test
    void resetLinkUsesAdminUrlOrPath() {
        settings.put("home_url", "https://other.example.org");
        settings.put("admin_url", "https://blog.example.ru/");
        assertThat(run("reset-password", "admin")).isZero();
        var prefix = "https://blog.example.ru/admin/set-password?token=";
        assertThat(out.toString()).startsWith(prefix);
        assertThat(err.toString()).isEmpty();
        var token = out.toString().strip().substring(prefix.length());
        assertThat(tokens.consume(token, "set_password")).contains(adminId);
    }

    @Test
    void printsPathOnlyWithoutAdminUrl() {
        settings.put("home_url", "https://other.example.org");
        assertThat(run("reset-password", "admin")).isZero();
        assertThat(err.toString().strip()).isEqualTo("admin_url is not set; open the link on the admin's host");
        assertThat(out.toString()).startsWith("/admin/set-password?token=");
    }

    @Test
    void unknownLoginExits1() {
        assertThat(run("reset-password", "nobody")).isOne();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("nobody");
    }

    @Test
    void cliClearsTheLastAdministrator() {
        users.setPassword(adminId, TestStore.PASSWORD_HASH);
        factors.putTotp(adminId, "seed");
        factors.confirmTotp(adminId, "seed", 1);
        settings.put("require_2fa", "true");
        assertThat(run("reset-2fa", "admin")).isZero();
        assertThat(out.toString().strip())
            .isEqualTo(
                    "Removed 1 two-factor methods for admin. They can sign in with their password."
                    + " They will be asked to add a new one."
            );
        assertThat(factors.hasFactor(adminId)).isFalse();
        var session = csrf()
            .contentType(JSON)
            .body(Map.of("login", "admin", "password", TestStore.PASSWORD))
            .post("/admin/api/login")
            .then()
            .statusCode(204)
            .extract()
            .cookie("sp_session");
        csrf().cookie("sp_session", session).get("/admin/api/session").then().body("enrollRequired", equalTo(true));
    }

    @Test
    void cliWithoutPolicyOmitsTheEnrollmentSentence() {
        assertThat(run("reset-2fa", "admin")).isZero();
        assertThat(out.toString().strip())
            .isEqualTo("Removed 0 two-factor methods for admin. They can sign in with their password.");
    }

    @Test
    void cliUnknownUserExits1() {
        assertThat(run("reset-2fa", "nobody")).isOne();
        assertThat(err.toString().strip()).isEqualTo("No such user: nobody");
    }

    private int run(String... args) {
        var cmd = new CommandLine(UserCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }
}
