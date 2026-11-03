package site.asm0dey.stillpress.publish.views;

import module java.base;

public record ArchiveView(
        String heading,
        List<ItemView> items,
        int page,
        int totalPages,
        String prevUrl,
        String nextUrl
) {}
