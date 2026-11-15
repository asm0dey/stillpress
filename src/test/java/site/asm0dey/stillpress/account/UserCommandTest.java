package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;
import site.asm0dey.stillpress.Fixtures;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@ExtendWith(MockitoExtension.class)
class UserCommandTest {
    private static final long ADMIN = 7;
    @Mock
    AuthorRepository users;
    @Mock
    SetPasswordLink link;
    @Mock
    SettingRepository settings;
    @Mock
    TwoFactorRepository factors;
    @Mock
    SessionRepository sessions;
    @Mock
    TwoFactorPolicy policy;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(CommandLine.Model.CommandSpec.create());
        cmd.addSubcommand("reset-password", new UserCommand.ResetPassword(users, link, settings));
        cmd.addSubcommand("reset-2fa", new UserCommand.Reset2fa(users, factors, sessions, policy));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
    }

    private void adminExists() {
        when(users.findByLoginOrEmail("admin"))
            .thenReturn(Optional.of(Fixtures.author(ADMIN, "admin", Role.ADMINISTRATOR, null)));
    }

    @Test
    void resetPasswordPrintsTheIssuedLink() {
        adminExists();
        when(settings.get("admin_url")).thenReturn(Optional.of("https://blog.example.ru/"));
        when(link.issue(ADMIN, TokenRepository.SET_PASSWORD, SetPasswordLink.MANUAL_TTL))
            .thenReturn("https://blog.example.ru/admin/set-password?token=t");
        assertThat(cmd.execute("reset-password", "admin")).isZero();
        assertThat(out.toString().strip()).isEqualTo("https://blog.example.ru/admin/set-password?token=t");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void resetPasswordWarnsWhenAdminUrlIsUnset() {
        adminExists();
        when(settings.get("admin_url")).thenReturn(Optional.empty());
        when(link.issue(ADMIN, TokenRepository.SET_PASSWORD, SetPasswordLink.MANUAL_TTL))
            .thenReturn("/admin/set-password?token=t");
        assertThat(cmd.execute("reset-password", "admin")).isZero();
        assertThat(err.toString().strip()).isEqualTo("admin_url is not set; open the link on the admin's host");
        assertThat(out.toString().strip()).isEqualTo("/admin/set-password?token=t");
    }

    @Test
    void resetPasswordForUnknownLoginExits1() {
        when(users.findByLoginOrEmail("nobody")).thenReturn(Optional.empty());
        assertThat(cmd.execute("reset-password", "nobody")).isOne();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString().strip()).isEqualTo("No such user: nobody");
        verify(link, never()).issue(anyLong(), any(), any());
    }

    @Test
    void reset2faClearsFactorsAndSessionsAndMentionsEnrollmentUnderPolicy() {
        adminExists();
        when(factors.clear(ADMIN)).thenReturn(1);
        when(policy.required()).thenReturn(true);
        assertThat(cmd.execute("reset-2fa", "admin")).isZero();
        assertThat(out.toString().strip())
            .isEqualTo(
                    "Removed 1 two-factor methods for admin. They can sign in with their password."
                    + " They will be asked to add a new one."
            );
        verify(sessions).deleteAllFor(ADMIN);
    }

    @Test
    void reset2faWithoutPolicyOmitsTheEnrollmentSentence() {
        adminExists();
        when(factors.clear(ADMIN)).thenReturn(0);
        when(policy.required()).thenReturn(false);
        assertThat(cmd.execute("reset-2fa", "admin")).isZero();
        assertThat(out.toString().strip())
            .isEqualTo("Removed 0 two-factor methods for admin. They can sign in with their password.");
    }

    @Test
    void reset2faForUnknownUserExits1AndTouchesNothing() {
        when(users.findByLoginOrEmail("nobody")).thenReturn(Optional.empty());
        assertThat(cmd.execute("reset-2fa", "nobody")).isOne();
        assertThat(err.toString().strip()).isEqualTo("No such user: nobody");
        verify(factors, never()).clear(anyLong());
        verify(sessions, never()).deleteAllFor(anyLong());
    }
}
