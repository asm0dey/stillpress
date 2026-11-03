package site.asm0dey.stillpress.importer;

import module java.base;

/**
 * Downloads one media file; empty when the source no longer has it.
 */
public interface MediaFetcher {
    Optional<byte[]> fetch(URI source);
}
