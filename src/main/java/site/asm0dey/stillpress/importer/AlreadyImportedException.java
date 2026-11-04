package site.asm0dey.stillpress.importer;

/**
 * Import is one-shot: thrown when the store already holds items.
 */
public class AlreadyImportedException extends Exception {
    public AlreadyImportedException() {
        this("store already contains imported items; import is one-shot");
    }

    public AlreadyImportedException(String message) {
        super(message);
    }
}
