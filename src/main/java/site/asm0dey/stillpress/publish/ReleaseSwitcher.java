package site.asm0dey.stillpress.publish;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.config.DataDir;

/**
 * Releases are complete site trees; {@code current} is a symlink swapped atomically to the newest one.
 */
@ApplicationScoped
public class ReleaseSwitcher {
    static final int KEEP = 3;
    private static final DateTimeFormatter NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private final DataDir dataDir;

    public ReleaseSwitcher(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Creates an empty release directory; names sort chronologically, a same-millisecond clash moves on to the next one.
     */
    public synchronized Path newRelease() {
        try {
            var t = Instant.now();
            while (true) {
                try {
                    return Files.createDirectory(dataDir.releases().resolve(NAME.format(t)));
                } catch (FileAlreadyExistsException e) {
                    t = t.plusMillis(1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Atomically points {@code current} at {@code release}, then prunes all but the newest {@value #KEEP} releases.
     */
    public synchronized void activate(Path release) {
        try {
            var current = dataDir.current();
            var tmp = current.resolveSibling("current.tmp");
            Files.deleteIfExists(tmp);
            Files.createSymbolicLink(tmp, current.getParent().relativize(release.toAbsolutePath()));
            Files.move(tmp, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            prune(release.toRealPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void prune(Path live) throws IOException {
        List<Path> all;
        try (var list = Files.list(dataDir.releases())) {
            all = list.filter(Files::isDirectory).sorted().toList();
        }
        for (var old : all.subList(0, Math.max(0, all.size() - KEEP))) {
            if (!old.toRealPath().equals(live)) {
                deleteTree(old);
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
