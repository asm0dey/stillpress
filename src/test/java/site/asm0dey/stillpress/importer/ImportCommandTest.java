package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ImportCommandTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    SettingRepository settings;
    @TempDir
    Path tmp;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @Test
    void importWritesDoneAndASecondImportExits1() throws Exception {
        store.reset();
        var wxr = sampleFile();
        assertThat(run("--wxr", wxr.toString())).isZero();
        assertThat(out.toString()).startsWith("Imported 7 items");
        assertThat(settings.get(ImportGuard.STATE)).contains("done");
        assertThat(run("--wxr", wxr.toString())).isOne();
        assertThat(err.toString()).contains("an import is already done");
    }

    @Test
    void runningImportElsewhereExits1() throws Exception {
        store.reset();
        settings.put(ImportGuard.STATE, "running");
        assertThat(run("--wxr", sampleFile().toString())).isOne();
        assertThat(err.toString()).contains("an import is already running");
        assertThat(settings.get(ImportGuard.STATE)).as("left alone").contains("running");
    }

    private Path sampleFile() throws IOException {
        var wxr = tmp.resolve("export.xml");
        try (var in = TestStore.sample()) {
            Files.copy(in, wxr, StandardCopyOption.REPLACE_EXISTING);
        }
        return wxr;
    }

    private int run(String... args) {
        var cmd = new CommandLine(ImportCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }

    @Test
    void parsesMediaLimit() {
        var cmd = new ImportCommand();
        new CommandLine(cmd).parseArgs("--wxr", "x.xml", "--media-limit", "100");
        assertThat(cmd.mediaLimit).isEqualTo(100);
    }

    @Test
    void mediaLimitIsOptional() {
        var cmd = new ImportCommand();
        new CommandLine(cmd).parseArgs("--wxr", "x.xml");
        assertThat(cmd.mediaLimit).isNull();
    }

    @Test
    void negativeMediaLimitIsRejectedWithExitCode2() {
        var exit = new CommandLine(new ImportCommand()).execute("--wxr", "x.xml", "--media-limit", "-1");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void siteOptionsDefaultToWordPressDefaults() {
        var cmd = new ImportCommand();
        new CommandLine(cmd).parseArgs("--wxr", "x.xml");
        assertThat(cmd.options()).isEqualTo(ImportOptions.DEFAULTS);
        assertThat(ImportOptions.DEFAULTS).isEqualTo(new ImportOptions(10, "category", "tag", 10));
    }

    @Test
    void parsesSiteOptions() {
        var cmd = new ImportCommand();
        new CommandLine(cmd)
            .parseArgs(
                    "--wxr",
                    "x.xml",
                    "--posts-per-page",
                    "5",
                    "--category-base",
                    "rubrika",
                    "--tag-base",
                    "metka",
                    "--posts-per-rss",
                    "20"
            );
        assertThat(cmd.options()).isEqualTo(new ImportOptions(5, "rubrika", "metka", 20));
    }

    @Test
    void invalidSiteOptionsAreRejectedWithExitCode2() {
        for (var args :
                List.of(
                        List.of("--posts-per-page", "0"),
                        List.of("--posts-per-rss", "-3"),
                        List.of("--category-base", "a/b"),
                        List.of("--category-base", ""),
                        List.of("--tag-base", ".."),
                        List.of("--tag-base", "a?b")
        )) {
            var all = new ArrayList<>(List.of("--wxr", "x.xml"));
            all.addAll(args);
            var cmd = new CommandLine(new ImportCommand());
            cmd.setErr(new PrintWriter(new StringWriter()));
            assertThat(cmd.execute(all.toArray(String[]::new))).as(args.toString()).isEqualTo(2);
        }
    }
}
