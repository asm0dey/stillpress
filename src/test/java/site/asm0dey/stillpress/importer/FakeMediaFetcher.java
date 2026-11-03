package site.asm0dey.stillpress.importer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import module java.base;

/**
 * Test fetcher: 3 bytes for every URL, empty for {@code /2020/05/c.jpg}, throws for URLs ending in {@link #failOn}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakeMediaFetcher implements MediaFetcher {
    private volatile String failOn;

    /**
     * A method, not a field: field writes land on the CDI client proxy.
     */
    public void failOn(String suffix) {
        failOn = suffix;
    }

    @Override
    public Optional<byte[]> fetch(URI source) {
        var s = source.toString();
        if (failOn != null && s.endsWith(failOn)) {
            throw new UncheckedIOException(new IOException("boom: " + s));
        }
        return s.endsWith("/2020/05/c.jpg") ? Optional.empty() : Optional.of(new byte[] {1, 2, 3});
    }
}
