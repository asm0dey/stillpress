package site.asm0dey.stillpress.importer;

/**
 * Import progress: running totals, reported after each stored item and each downloaded media file.
 */
public interface ImportListener {
    ImportListener NONE = new ImportListener() {
    };

    default void item(int done) {}

    default void media(int done) {}
}
