package site.asm0dey.stillpress.publish.views;

import module java.base;

/**
 * One post in an archive list, rendered once into {@code item_fragment}.
 */
public record EntryView(String title, String permalinkEncoded, Instant publishedAt, String excerptHtml) {
    /**
     * The one place an archive entry's excerpt is chosen.
     */
    public static EntryView of(ItemView v) {
        return new EntryView(v.title(), v.permalinkEncoded(), v.publishedAt(), v.excerptHtml());
    }
}
