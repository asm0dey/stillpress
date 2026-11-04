package site.asm0dey.stillpress.account;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import site.asm0dey.stillpress.store.AuthorRepository;

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

        @Override
        public Integer call() {
            var user = users.findByLoginOrEmail(login);
            if (user.isEmpty()) {
                spec.commandLine().getErr().println("No such user: " + login);
                return 1;
            }
            spec.commandLine().getOut().println(link.issue(user.get().getId()));
            return 0;
        }
    }
}
