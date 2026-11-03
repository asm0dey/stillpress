package site.asm0dey.stillpress.config;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DataDir {
    private final Path root;

    public DataDir(@ConfigProperty(name = "stillpress.data-dir") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public Path root() {
        return dir(root);
    }

    public Path media() {
        return dir(root.resolve("media"));
    }

    public Path output() {
        return dir(root.resolve("output"));
    }

    public Path releases() {
        return dir(output().resolve("releases"));
    }

    /**
     * The live-site symlink; deliberately not created.
     */
    public Path current() {
        return output().resolve("current");
    }

    private static Path dir(Path p) {
        try {
            return Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
