package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

// through Quarkus: the commands are CDI beans with constructor injection, which picocli alone cannot build
@QuarkusMainTest
class StillpressCommandTest {
    @Test
    @Launch("--help")
    void serveIsRegistered(LaunchResult result) {
        assertThat(result.getOutput()).contains("serve", "import", "publish", "user", "nginx-config");
    }
}
