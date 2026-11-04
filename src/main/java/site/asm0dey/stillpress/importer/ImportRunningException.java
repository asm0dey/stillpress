package site.asm0dey.stillpress.importer;

/**
 * An admin write arrived while an import runs; the site's origin is not decided yet.
 */
public class ImportRunningException extends RuntimeException {
    public ImportRunningException() {
        super("an import is running");
    }
}
