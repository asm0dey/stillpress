package site.asm0dey.stillpress;

import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.account.SecretsCommand;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.config.Keys;

/**
 * Every command but {@code secrets} and help refuses to run while the stored secrets do not decrypt under the current
 * keyset: they belong to another one, which the operator must restore or give up. A keyset this start generated is
 * deleted on every such path, help and parse errors included, so it is never taken for the real one later.
 */
@ApplicationScoped
public class KeysetGuard {
    @Produces
    CommandLine commandLine(
            PicocliCommandLineFactory factory,
            DataDir dataDir,
            @ConfigProperty(name = "stillpress.keys-dir") Optional<Path> keysDir,
            Secrets secrets
    ) {
        return guard(factory.create(), () -> Keys.get(dataDir.root(), keysDir), secrets);
    }

    static CommandLine guard(CommandLine commandLine, Supplier<Keys> keys, Secrets secrets) {
        var parseErrors = commandLine.getParameterExceptionHandler();
        return commandLine
            .setParameterExceptionHandler((e, args) -> {
                discardOrphaned(keys.get(), secrets);
                return parseErrors.handleParseException(e, args);
            })
            .setExecutionStrategy(parseResult -> {
                var commands = parseResult.asCommandLineList();
                var exempt = commands
                    .stream()
                    .anyMatch(c -> c.getCommand() instanceof SecretsCommand
                            || c.isUsageHelpRequested()
                            || c.isVersionHelpRequested());
                if (exempt) {
                    discardOrphaned(keys.get(), secrets);
                } else {
                    var code = check(keys.get(), secrets, commands.getLast().getErr());
                    if (code != 0) {
                        return code;
                    }
                }
                return new CommandLine.RunLast().execute(parseResult);
            });
    }

    private static void discardOrphaned(Keys keys, Secrets secrets) {
        if (keys.generatedNow() && !secrets.readableWith(keys)) {
            keys.discardGenerated();
        }
    }

    /**
     * Refuses a keyset under which the stored secrets do not decrypt, and deletes it when this start generated it.
     */
    public static int check(Keys keys, Secrets secrets, PrintWriter err) {
        if (secrets.readableWith(keys)) {
            return 0;
        }
        var dir = keys.dir();
        if (keys.generatedNow()) {
            keys.discardGenerated();
            err.println(
                    "Stillpress created a new encryption keyset in "
                    + dir
                    + ", but the database holds secrets encrypted with another one."
            );
        } else {
            err.println("The keyset in " + dir + " does not decrypt the secrets stored in the database.");
        }
        err.println(
                "Restore the previous keyset files (aead.json, prf.json) into "
                + dir
                + ", or run `stillpress secrets reset` to drop the stored secrets."
        );
        return 1;
    }
}
