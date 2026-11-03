package site.asm0dey.stillpress.account;

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
    long adminId;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @BeforeEach
    void reset() {
        store.reset();
        adminId = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).getId();
    }

    @Test
    void printsSetPasswordLinkUnderHomeUrl() {
        settings.put("home_url", "https://blog.example.ru/");
        assertThat(run("reset-password", "admin")).isZero();
        var prefix = "https://blog.example.ru/admin/set-password?token=";
        assertThat(out.toString()).startsWith(prefix);
        var token = out.toString().strip().substring(prefix.length());
        assertThat(tokens.consume(token, "set_password")).contains(adminId);
    }

    @Test
    void printsPathOnlyWithoutHomeUrl() {
        assertThat(run("reset-password", "admin")).isZero();
        assertThat(out.toString()).startsWith("/admin/set-password?token=");
    }

    @Test
    void unknownLoginExits1() {
        assertThat(run("reset-password", "nobody")).isOne();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("nobody");
    }

    private int run(String... args) {
        var cmd = new CommandLine(UserCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }
}
