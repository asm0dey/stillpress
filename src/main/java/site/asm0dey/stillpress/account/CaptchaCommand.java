package site.asm0dey.stillpress.account;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "captcha",
        description = "Manage the CAPTCHA on the admin's public forms",
        subcommands = CaptchaCommand.None.class
)
public class CaptchaCommand {
    @Command(name = "none", description = "Turn the CAPTCHA off, e.g. when a broken Turnstile key locks everyone out")
    public static class None implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Inject
        Captcha captcha;

        @Override
        public Integer call() {
            captcha.turnOff();
            spec.commandLine().getOut().println("CAPTCHA is now off.");
            if (captcha.locked()) {
                spec.commandLine().getErr().println(
                        "STILLPRESS_CAPTCHA_PROVIDER is set and overrides this; unset it and restart."
                );
            }
            return 0;
        }
    }
}
