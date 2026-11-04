package site.asm0dey.stillpress.importer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import module java.base;

/**
 * Test fetcher: 3 bytes for every URL, empty for {@code /2020/05/c.jpg} and any {@link #missing} suffix, throws for
 * {@link #failOn}. Records every URL it was asked for.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakeMediaFetcher implements MediaFetcher {
    private volatile String failOn;
    private volatile String errorOn;
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final List<String> fetched = new CopyOnWriteArrayList<>();

    // methods, not fields: field writes land on the CDI client proxy
    public void failOn(String suffix) {
        failOn = suffix;
    }

    /**
     * Throws an {@link Error}, not an exception, for this suffix.
     */
    public void errorOn(String suffix) {
        errorOn = suffix;
    }

    public void missing(String suffix) {
        missing.add(suffix);
    }

    public List<String> fetched() {
        return List.copyOf(fetched);
    }

    public void reset() {
        failOn = null;
        errorOn = null;
        missing.clear();
        fetched.clear();
    }

    @Override
    public Optional<byte[]> fetch(URI source) {
        var s = source.toString();
        fetched.add(s);
        if (failOn != null && s.endsWith(failOn)) {
            throw new UncheckedIOException(new IOException("boom: " + s));
        }
        if (errorOn != null && s.endsWith(errorOn)) {
            throw new AssertionError("fetcher error: " + s);
        }
        if (s.endsWith("/2020/05/c.jpg") || missing.stream().anyMatch(s::endsWith)) {
            return Optional.empty();
        }
        return Optional.of(new byte[] {1, 2, 3});
    }
}
