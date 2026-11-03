package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
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
}
