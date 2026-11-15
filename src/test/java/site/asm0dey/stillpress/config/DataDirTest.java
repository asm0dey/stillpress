package site.asm0dey.stillpress.config;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirTest {
    @TempDir
    Path tmp;

    @Test
    void createsSubdirectories() {
        var dataDir = new DataDir(tmp.toString());
        assertThat(dataDir.media()).isDirectory().startsWith(dataDir.root());
        assertThat(dataDir.releases()).isDirectory();
        assertThat(dataDir.current()).isEqualTo(dataDir.output().resolve("current"));
    }
}
