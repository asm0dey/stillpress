package site.asm0dey.stillpress.importer;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "import", description = "Import a WordPress WXR export (one-shot)")
public class ImportCommand implements Callable<Integer> {
    @Option(names = "--wxr", required = true, description = "WXR export file")
    Path wxr;
    @Inject
    WordPressImporter importer;

    @Override
    public Integer call() throws IOException {
        try (var in = new BufferedInputStream(Files.newInputStream(wxr))) {
            System.out.print(importer.importWxr(in).toText());
            return 0;
        } catch (AlreadyImportedException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
