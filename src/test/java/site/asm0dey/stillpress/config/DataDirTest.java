package site.asm0dey.stillpress.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DataDirTest {
    @Inject DataDir dataDir;

    @Test
    void createsSubdirectories() {
        assertThat(dataDir.media()).isDirectory().startsWith(dataDir.root());
        assertThat(dataDir.releases()).isDirectory();
        assertThat(dataDir.current()).isEqualTo(dataDir.output().resolve("current"));
    }
}
