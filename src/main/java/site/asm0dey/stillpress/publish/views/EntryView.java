package site.asm0dey.stillpress.publish.views;

import module java.base;

/**
 * One post in an archive list, rendered once into {@code item_fragment}.
 */
public record EntryView(String title, String permalinkEncoded, Instant publishedAt, String excerptHtml) {
    /**
     * The one place an archive entry's excerpt is chosen: the stored one, else the automatic one (WordPress's
     * {@code wp_trim_excerpt}). Feeds keep the stored excerpt.
     */
    public static EntryView of(ItemView v) {
        var stored = v.excerptHtml() != null && !v.excerptHtml().isBlank();
        return new EntryView(
                v.title(),
                v.permalinkEncoded(),
                v.publishedAt(),
                stored ? v.excerptHtml() : ItemView.trimmed(v.contentHtml(), " […]")
        );
    }
}
