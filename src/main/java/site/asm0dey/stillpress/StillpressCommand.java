package site.asm0dey.stillpress;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;
import site.asm0dey.stillpress.account.UserCommand;
import site.asm0dey.stillpress.importer.ImportCommand;
import site.asm0dey.stillpress.publish.PublishCommand;
import site.asm0dey.stillpress.verify.VerifyUrlsCommand;

@TopCommand
@Command(
        name = "stillpress",
        mixinStandardHelpOptions = true,
        subcommands = {
        StillpressCommand.ServeCommand.class,
        ImportCommand.class,
        PublishCommand.class,
        VerifyUrlsCommand.class,
        UserCommand.class
        }
)
public class StillpressCommand implements Runnable {
    @Override
    public void run() {
        // no args behaves like `serve`
        new ServeCommand().run();
    }

    @Command(name = "serve", description = "Serve the published site (Mode 1) and the redirect fallback (default)")
    public static class ServeCommand implements Runnable {
        @Override
        public void run() {
            Quarkus.waitForExit();
        }
    }
}
