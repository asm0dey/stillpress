package site.asm0dey.stillpress.importer;

/**
 * Site settings a WXR export does not carry; stored as settings on import. Defaults are WordPress's.
 */
public record ImportOptions(int postsPerPage, String categoryBase, String tagBase, int postsPerRss) {
    public static final ImportOptions DEFAULTS = new ImportOptions(10, "category", "tag", 10);

    public ImportOptions {
        positive("--posts-per-page", postsPerPage);
        positive("--posts-per-rss", postsPerRss);
        segment("--category-base", categoryBase);
        segment("--tag-base", tagBase);
    }

    private static void positive(String name, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer, got " + n);
        }
    }

    private static void segment(String name, String s) {
        if (s == null
                || s.isBlank()
                || s.equals(".")
                || s.equals("..")
                || s
                    .chars()
                    .anyMatch(c -> "/\\?#%".indexOf(c) >= 0
                            || Character.isWhitespace(c)
                            || Character.isISOControl(c))) {
            throw new IllegalArgumentException(name + " must be a single non-empty path segment, got '" + s + "'");
        }
    }
}
