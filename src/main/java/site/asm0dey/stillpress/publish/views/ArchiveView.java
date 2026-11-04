package site.asm0dey.stillpress.publish.views;

import module java.base;

/**
 * @param entries ready HTML of each entry, from {@code plain/entry}
 */
public record ArchiveView(
        String heading,
        List<String> entries,
        int page,
        int totalPages,
        String prevUrl,
        String nextUrl
) {}
