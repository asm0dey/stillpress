package site.asm0dey.stillpress.account;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@Command(name = "user", description = "Manage users", subcommands = UserCommand.ResetPassword.class)
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
            spec.commandLine().getOut().println(link.issue(user.get().getId(), SetPasswordLink.MANUAL_TTL));
            return 0;
        }
    }
}
