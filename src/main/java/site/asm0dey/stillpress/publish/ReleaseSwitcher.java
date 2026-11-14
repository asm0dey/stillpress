package site.asm0dey.stillpress.publish;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.config.DataDir;

/**
 * Releases are complete site trees; {@code current} is a symlink swapped atomically to the newest one.
 */
@ApplicationScoped
public class ReleaseSwitcher {
    static final int KEEP = 3;
    private static final DateTimeFormatter NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final Logger LOG = Logger.getLogger(ReleaseSwitcher.class);
    private final DataDir dataDir;
    private Instant last;

    @Inject
    public ReleaseSwitcher(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Creates an empty release directory; names sort chronologically, a same-millisecond clash moves on to the next one.
     */
    public synchronized Path newRelease() {
        try {
            if (last == null) {
                last = newestExisting();
            }
            // strictly after the last name handed out, so a burst never produces a name older than a surviving one
            var t = Instant.now().isAfter(last) ? Instant.now() : last.plusMillis(1);
            while (true) {
                try {
                    var dir = Files.createDirectory(dataDir.releases().resolve(NAME.format(t)));
                    last = t;
                    return dir;
                } catch (FileAlreadyExistsException e) {
                    t = t.plusMillis(1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The newest release name on disk as a time, so names stay ordered across restarts and clock skew.
     */
    private Instant newestExisting() throws IOException {
        try (var list = Files.list(dataDir.releases())) {
            return list
                .map(p -> p.getFileName().toString())
                .map(n -> {
                    try {
                        return NAME.parse(n, Instant::from);
                    } catch (DateTimeParseException e) {
                        return Instant.EPOCH;
                    }
                })
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        }
    }

    /**
     * Atomically points {@code current} at {@code release}.
     */
    public synchronized void activate(Path release) {
        try {
            var current = dataDir.current();
            var tmp = current.resolveSibling("current.tmp");
            Files.deleteIfExists(tmp);
            Files.createSymbolicLink(tmp, current.getParent().relativize(release.toAbsolutePath()));
            Files.move(tmp, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deletes all but the newest {@value #KEEP} releases, never the live one. Housekeeping: a failure here must never
     * break a publish, so each release is tried on its own.
     */
    public synchronized void prune() {
        try (var list = Files.list(dataDir.releases())) {
            var live = dataDir.current().toRealPath();
            var all = list.filter(Files::isDirectory).sorted().toList();
            for (var old : all.subList(0, Math.max(0, all.size() - KEEP))) {
                try {
                    if (!old.toRealPath().equals(live)) {
                        deleteTree(old);
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.warnf(e, "Could not prune old release %s", old);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warnf(e, "Could not list releases to prune");
        }
    }

    public static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
