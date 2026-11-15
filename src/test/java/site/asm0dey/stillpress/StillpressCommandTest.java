package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

// through Quarkus: the commands are CDI beans with constructor injection, which picocli alone cannot build; the
// shared test application's factory builds them, so this needs no launch of its own
@QuarkusTest
class StillpressCommandTest {
    @Inject
    CommandLine.IFactory factory;

    @Test
    void serveIsRegistered() {
        assertThat(new CommandLine(StillpressCommand.class, factory).getUsageMessage())
            .contains("serve", "import", "publish", "user", "nginx-config");
    }
}
