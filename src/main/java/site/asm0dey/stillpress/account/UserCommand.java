package site.asm0dey.stillpress.account;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@Command(
        name = "user",
        description = "Manage users",
        subcommands = {UserCommand.ResetPassword.class, UserCommand.Reset2fa.class}
)
public class UserCommand {
    @Command(name = "reset-password", description = "Print a single-use link (valid 24 h) to set a user's password")
    public static class ResetPassword implements Callable<Integer> {
        @Parameters(paramLabel = "<login>", description = "Login or email")
        String login;
        @Spec
        CommandSpec spec;
        @Inject
        AuthorRepository users;
        @Inject
        SetPasswordLink link;
        @Inject
        SettingRepository settings;

        @Override
        public Integer call() {
            var user = users.findByLoginOrEmail(login);
            if (user.isEmpty()) {
                spec.commandLine().getErr().println("No such user: " + login);
                return 1;
            }
            if (settings.get("admin_url").isEmpty()) {
                spec.commandLine().getErr().println("admin_url is not set; open the link on the admin's host");
            }
            spec
                .commandLine()
                .getOut()
                .println(link.issue(user.get().getId(), TokenRepository.SET_PASSWORD, SetPasswordLink.MANUAL_TTL));
            return 0;
        }
    }

    @Command(name = "reset-2fa", description = "Remove a user's two-factor methods and sign them out")
    public static class Reset2fa implements Callable<Integer> {
        @Parameters(paramLabel = "<login>", description = "Login or email")
        String login;
        @Spec
        CommandSpec spec;
        @Inject
        AuthorRepository users;
        @Inject
        TwoFactorRepository factors;
        @Inject
        SessionRepository sessions;
        @Inject
        TwoFactorPolicy policy;

        @Override
        @Transactional
        public Integer call() {
            var user = users.findByLoginOrEmail(login);
            if (user.isEmpty()) {
                spec.commandLine().getErr().println("No such user: " + login);
                return 1;
            }
            var id = user.get().getId();
            var removed = factors.clear(id);
            sessions.deleteAllFor(id);
            spec
                .commandLine()
                .getOut()
                .println(
                        "Removed "
                        + removed
                        + " two-factor methods for "
                        + login
                        + ". They can sign in with their password."
                        + (policy.required() ? " They will be asked to add a new one." : "")
                );
            return 0;
        }
    }
}
