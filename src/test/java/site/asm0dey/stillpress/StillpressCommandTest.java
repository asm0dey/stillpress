package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class StillpressCommandTest {
    @Test
    void serveIsRegistered() {
        assertThat(new CommandLine(new StillpressCommand()).getSubcommands())
            .containsKey("serve")
            .containsKey("import")
            .containsKey("publish")
            .containsKey("user");
    }
}
