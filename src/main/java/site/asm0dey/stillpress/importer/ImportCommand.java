package site.asm0dey.stillpress.importer;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "import", description = "Import a WordPress WXR export (one-shot)")
public class ImportCommand implements Callable<Integer> {
    @Option(names = "--wxr", required = true, description = "WXR export file")
    Path wxr;
    @Option(
            names = "--media-limit",
            description = "Download at most N media files, leave the rest at the source (for test imports)"
    )
    Integer mediaLimit;
    @Inject
    WordPressImporter importer;

    @Override
    public Integer call() throws IOException {
        if (mediaLimit != null && mediaLimit < 0) {
            System.err.println("--media-limit must be 0 or more, got " + mediaLimit);
            return 2;
        }
        try (var in = new BufferedInputStream(Files.newInputStream(wxr))) {
            var report = mediaLimit == null ? importer.importWxr(in) : importer.importWxr(in, mediaLimit);
            System.out.print(report.toText(mediaLimit));
            return 0;
        } catch (AlreadyImportedException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
