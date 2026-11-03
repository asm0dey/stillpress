package site.asm0dey.stillpress;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;

@TopCommand
@Command(name = "stillpress", mixinStandardHelpOptions = true, subcommands = {})
public class StillpressCommand implements Runnable {

    @Override
    public void run() {
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
