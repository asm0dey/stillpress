package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ImportCommandTest {
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
