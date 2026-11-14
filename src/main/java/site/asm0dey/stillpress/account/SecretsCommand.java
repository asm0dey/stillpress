package site.asm0dey.stillpress.account;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import site.asm0dey.stillpress.store.SecretRepository;

@Command(name = "secrets", description = "Manage encrypted secrets", subcommands = SecretsCommand.Reset.class)
public class SecretsCommand {
    @Command(name = "reset", description = "Delete every stored secret, so Stillpress starts with a new keyset")
    public static class Reset implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Inject
        SecretRepository secrets;

        @Override
        public Integer call() {
            var deleted = secrets.deleteAll();
            spec
                .commandLine()
                .getOut()
                .println(
                        "Deleted " + deleted + " stored secrets. Re-enter the SMTP password and CAPTCHA secret in the admin."
                );
            return 0;
        }
    }
}
