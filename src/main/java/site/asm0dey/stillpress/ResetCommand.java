package site.asm0dey.stillpress;

import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.db.DefaultSchema;
import site.asm0dey.stillpress.importer.ImportRunningException;

@Command(name = "reset", description = "Delete all content, users and settings, back to a fresh install")
public class ResetCommand implements Callable<Integer> {
    @Option(names = "--yes", description = "Really delete; without it, only show what would go")
    boolean yes;
    @Option(
            names = "--force",
            description = "With --yes: also reset when an import is marked as running. Only when the server is stopped"
            + " and no import runs, e.g. after a crash mid-import"
    )
    boolean force;
    @Spec
    CommandSpec spec;
    @Inject
    SiteReset reset;
    @Inject
    DSLContext db;
    @Inject
    DataDir dataDir;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        if (!yes) {
            out.println("This deletes every row below and the files in " + dataDir.root() + ":");
            // one count per table: a dry run's report, not bulk work
            for (var t : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
                out.println("  " + t.getName() + ": " + db.fetchCount(t));
            }
            out.println("Run again with --yes to delete it. Copy the data directory first if you may want it back.");
            return 1;
        }
        boolean filesOk;
        try {
            filesOk = reset.wipe(force);
        } catch (ImportRunningException e) {
            spec
                .commandLine()
                .getErr()
                .println(
                        "An import is marked as running. Nothing was deleted. If it stopped mid-way, stop the server"
                        + " and any import, then run `stillpress reset --yes --force`."
                );
            return 1;
        }
        if (!filesOk) {
            spec.commandLine().getErr().println("The database is reset, but cleaning up the files failed; see the log.");
            return 1;
        }
        out.println("Reset. The next `stillpress serve` start prints a setup token for the wizard.");
        return 0;
    }
}
