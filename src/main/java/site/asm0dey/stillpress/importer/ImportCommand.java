package site.asm0dey.stillpress.importer;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "import", description = "Import a WordPress WXR export (one-shot)")
public class ImportCommand implements Callable<Integer> {
    @Option(names = "--wxr", required = true, description = "WXR export file")
    Path wxr;
    @Option(
            names = "--media-limit",
            description = "Download at most N media files, leave the rest at the source (for test imports)"
    )
    Integer mediaLimit;
    @Option(
            names = "--posts-per-page",
            defaultValue = "10",
            description = "Posts per archive page (default: ${DEFAULT-VALUE})"
    )
    int postsPerPage;
    @Option(names = "--posts-per-rss", defaultValue = "10", description = "Items per feed (default: ${DEFAULT-VALUE})")
    int postsPerRss;
    @Option(
            names = "--category-base",
            defaultValue = "category",
            description = "Category URL base (default: ${DEFAULT-VALUE})"
    )
    String categoryBase;
    @Option(names = "--tag-base", defaultValue = "tag", description = "Tag URL base (default: ${DEFAULT-VALUE})")
    String tagBase;
    @Spec
    CommandSpec spec;
    @Inject
    WordPressImporter importer;
    @Inject
    ImportGuard guard;

    ImportOptions options() {
        return new ImportOptions(postsPerPage, categoryBase, tagBase, postsPerRss);
    }

    @Override
    public Integer call() throws IOException {
        if (mediaLimit != null && mediaLimit < 0) {
            spec.commandLine().getErr().println("--media-limit must be 0 or more, got " + mediaLimit);
            return 2;
        }
        ImportOptions options;
        try {
            options = options();
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
        try (var in = new BufferedInputStream(Files.newInputStream(wxr))) {
            try {
                guard.begin();
            } catch (AlreadyImportedException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
            ImportReport report;
            try {
                report = importer.importWxr(in, mediaLimit == null ? Integer.MAX_VALUE : mediaLimit, options);
            } catch (AlreadyImportedException e) {
                guard.failed(e.getMessage());
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            } catch (RuntimeException e) {
                guard.failed(e.getMessage());
                throw e;
            }
            guard.done();
            var out = spec.commandLine().getOut();
            out.print(report.toText(mediaLimit));
            out.flush();
            return 0;
        }
    }
}
