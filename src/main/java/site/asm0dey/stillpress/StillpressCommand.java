package site.asm0dey.stillpress;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;
import site.asm0dey.stillpress.importer.ImportCommand;
import site.asm0dey.stillpress.publish.PublishCommand;

@TopCommand
@Command(
        name = "stillpress",
        mixinStandardHelpOptions = true,
        subcommands = {StillpressCommand.ServeCommand.class, ImportCommand.class, PublishCommand.class}
)
public class StillpressCommand implements Runnable {
    @Override
    public void run() {
        // no args behaves like `serve`
        new ServeCommand().run();
    }

    @Command(name = "serve", description = "Run the admin and preview server (default)")
    public static class ServeCommand implements Runnable {
        @Override
        public void run() {
            Quarkus.waitForExit();
        }
    }
}
