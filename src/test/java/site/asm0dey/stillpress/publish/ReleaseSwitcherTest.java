package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.asm0dey.stillpress.config.DataDir;

class ReleaseSwitcherTest {
    @TempDir
    Path tmp;

    private ReleaseSwitcher switcher(DataDir dir) {
        return new ReleaseSwitcher(dir);
    }

    @Test
    void crashBeforeActivateKeepsPreviousCurrent() throws Exception {
        var dir = new DataDir(tmp.toString());
        var s = switcher(dir);
        var r1 = s.newRelease();
        Files.writeString(r1.resolve("index.html"), "one");
        s.activate(r1);
        var r2 = s.newRelease();
        Files.writeString(r2.resolve("half.html"), "partial");
        // no activate: the process "crashed"
        assertThat(dir.current().toRealPath()).isEqualTo(r1.toRealPath());
        assertThat(dir.current().resolve("index.html")).hasContent("one");
    }

    @Test
    void keepsThreeNewest() throws Exception {
        var dir = new DataDir(tmp.toString());
        var s = switcher(dir);
        Path last = null;
        for (int i = 0; i < 5; i++) {
            last = s.newRelease();
            s.activate(last);
        }
        try (var list = Files.list(dir.releases())) {
            assertThat(list.toList()).hasSize(3).contains(last);
        }
        assertThat(dir.current().toRealPath()).isEqualTo(last.toRealPath());
    }

    @Test
    void releasesCreatedInTheSameMillisecondDoNotCollide() {
        var s = switcher(new DataDir(tmp.toString()));
        var names = new ArrayList<Path>();
        for (int i = 0; i < 50; i++) {
            names.add(s.newRelease());
        }
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void neverPrunesTheReleaseCurrentPointsAt() throws Exception {
        var dir = new DataDir(tmp.toString());
        var s = switcher(dir);
        var oldest = s.newRelease();
        for (int i = 0; i < 4; i++) {
            s.newRelease();
        }
        s.activate(oldest);
        assertThat(oldest).exists();
        assertThat(dir.current().toRealPath()).isEqualTo(oldest.toRealPath());
    }

    @Test
    void pruneFailureNeverBreaksTheSwapOrDeletesTheLiveRelease() throws Exception {
        var dir = new DataDir(tmp.toString());
        assumeFalse("root".equals(System.getProperty("user.name")), "root ignores read-only directories");
        var s = switcher(dir);
        var releases = new ArrayList<Path>();
        Path last = null;
        try {
            for (int i = 0; i < 5; i++) {
                last = s.newRelease();
                releases.add(last);
                Files.writeString(last.resolve("index.html"), "r" + i);
                // a read-only release cannot be emptied, so pruning it fails
                Files.setPosixFilePermissions(last, PosixFilePermissions.fromString("r-xr-xr-x"));
                s.activate(last);
            }
            assertThat(dir.current().toRealPath()).isEqualTo(last.toRealPath());
            assertThat(dir.current().resolve("index.html")).hasContent("r4");
            assertThat(releases.getFirst().resolve("index.html")).as("prune was refused").exists();
        } finally {
            for (var r : releases) {
                Files.setPosixFilePermissions(r, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    @Test
    void namesStaySortedAfterExistingReleasesEvenWithClockBehind() throws Exception {
        var dir = new DataDir(tmp.toString());
        Files.createDirectory(dir.releases().resolve("29990101-000000-000"));
        var fresh = switcher(dir).newRelease();
        assertThat(fresh.getFileName().toString()).isGreaterThan("29990101-000000-000");
    }
}
