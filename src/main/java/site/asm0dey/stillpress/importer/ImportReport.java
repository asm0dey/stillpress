package site.asm0dey.stillpress.importer;

import module java.base;

public record ImportReport(
        int items,
        int comments,
        int media,
        int reusableBlocks,
        List<String> unknownBlocks,
        List<String> unknownShortcodes,
        List<String> missingMedia,
        List<String> passwordProtected,
        List<String> skippedPostTypes,
        List<String> skippedMedia
) {
    public String toText() {
        return toText(null);
    }

    /**
     * @param mediaLimit the {@code --media-limit} in force, named in the skipped-media heading
     */
    public String toText(Integer mediaLimit) {
        var sb = new StringBuilder();
        sb
            .append("Imported ")
            .append(items)
            .append(" items, ")
            .append(comments)
            .append(" comments, ")
            .append(media)
            .append(" media files, ")
            .append(reusableBlocks)
            .append(" reusable blocks\n");
        section(sb, "Unknown blocks", unknownBlocks);
        section(sb, "Unknown shortcodes", unknownShortcodes);
        section(sb, "Missing media", missingMedia);
        section(sb, "Password-protected", passwordProtected);
        section(sb, "Skipped post types", skippedPostTypes);
        if (!skippedMedia.isEmpty()) {
            sb
                .append("Skipped media")
                .append(mediaLimit == null ? "" : " (limit " + mediaLimit + ")")
                .append(": ")
                .append(skippedMedia.size())
                .append('\n');
            skippedMedia
                .stream()
                .limit(20)
                .forEach(v -> sb.append("  ").append(v).append('\n'));
            if (skippedMedia.size() > 20) {
                sb.append("  …and ").append(skippedMedia.size() - 20).append(" more\n");
            }
        }
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<String> values) {
        if (!values.isEmpty()) {
            sb.append(title).append(" (").append(values.size()).append("):\n");
            values.forEach(v -> sb.append("  ").append(v).append('\n'));
        }
    }
}
