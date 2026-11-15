package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class ImportCommandTest {
    @Mock
    WordPressImporter importer;
    @Mock
    ImportGuard guard;
    @TempDir
    Path tmp;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    private static ImportReport report(int items) {
        return new ImportReport(
                items,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private Path wxr() throws IOException {
        return Files.writeString(tmp.resolve("export.xml"), "<rss/>");
    }

    private CommandLine command() {
        var cmd = new CommandLine(new ImportCommand(importer, guard));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd;
    }

    @Test
    void importPrintsTheReportAndMarksTheGuardDone() throws Exception {
        when(importer.importWxr(any(), eq(Integer.MAX_VALUE), eq(ImportOptions.DEFAULTS))).thenReturn(report(9));
        assertThat(command().execute("--wxr", wxr().toString())).isZero();
        assertThat(out.toString()).startsWith("Imported 9 items");
        verify(guard).begin();
        verify(guard).done();
    }

    @Test
    void mediaLimitAndSiteOptionsReachTheImporter() throws Exception {
        var options = new ImportOptions(5, "rubrika", "metka", 20);
        when(importer.importWxr(any(), eq(100), eq(options))).thenReturn(report(1));
        assertThat(command()
            .execute(
                    "--wxr",
                    wxr().toString(),
                    "--media-limit",
                    "100",
                    "--posts-per-page",
                    "5",
                    "--category-base",
                    "rubrika",
                    "--tag-base",
                    "metka",
                    "--posts-per-rss",
                    "20"
            )
        )
            .isZero();
    }

    @Test
    void guardRefusalExits1WithItsMessageAndImportsNothing() throws Exception {
        doThrow(new AlreadyImportedException("an import is already done")).when(guard).begin();
        assertThat(command().execute("--wxr", wxr().toString())).isOne();
        assertThat(err.toString()).contains("an import is already done");
        verify(importer, never()).importWxr(any(), anyInt(), any());
        verify(guard, never()).done();
    }

    @Test
    void importerRefusalMarksTheGuardFailedAndExits1() throws Exception {
        when(importer.importWxr(any(), anyInt(), any())).thenThrow(new AlreadyImportedException("site has content"));
        assertThat(command().execute("--wxr", wxr().toString())).isOne();
        assertThat(err.toString()).contains("site has content");
        verify(guard).failed("site has content");
        verify(guard, never()).done();
    }

    @Test
    void importerCrashMarksTheGuardFailedAndExits1() throws Exception {
        when(importer.importWxr(any(), anyInt(), any())).thenThrow(new IllegalStateException("boom"));
        assertThat(command().execute("--wxr", wxr().toString())).isOne();
        assertThat(err.toString()).contains("boom");
        verify(guard).failed("boom");
        verify(guard, never()).done();
    }

    @Test
    void parsesMediaLimit() {
        var cmd = new ImportCommand(null, null);
        new CommandLine(cmd).parseArgs("--wxr", "x.xml", "--media-limit", "100");
        assertThat(cmd.mediaLimit).isEqualTo(100);
    }

    @Test
    void mediaLimitIsOptional() {
        var cmd = new ImportCommand(null, null);
        new CommandLine(cmd).parseArgs("--wxr", "x.xml");
        assertThat(cmd.mediaLimit).isNull();
    }

    @Test
    void negativeMediaLimitIsRejectedWithExitCode2() {
        var exit = new CommandLine(new ImportCommand(null, null)).execute("--wxr", "x.xml", "--media-limit", "-1");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void siteOptionsDefaultToWordPressDefaults() {
        var cmd = new ImportCommand(null, null);
        new CommandLine(cmd).parseArgs("--wxr", "x.xml");
        assertThat(cmd.options()).isEqualTo(ImportOptions.DEFAULTS);
        assertThat(ImportOptions.DEFAULTS).isEqualTo(new ImportOptions(10, "category", "tag", 10));
    }

    @Test
    void parsesSiteOptions() {
        var cmd = new ImportCommand(null, null);
        new CommandLine(cmd)
            .parseArgs(
                    "--wxr",
                    "x.xml",
                    "--posts-per-page",
                    "5",
                    "--category-base",
                    "rubrika",
                    "--tag-base",
                    "metka",
                    "--posts-per-rss",
                    "20"
            );
        assertThat(cmd.options()).isEqualTo(new ImportOptions(5, "rubrika", "metka", 20));
    }

    @Test
    void invalidSiteOptionsAreRejectedWithExitCode2() {
        for (var args :
                List.of(
                        List.of("--posts-per-page", "0"),
                        List.of("--posts-per-rss", "-3"),
                        List.of("--category-base", "a/b"),
                        List.of("--category-base", ""),
                        List.of("--tag-base", ".."),
                        List.of("--tag-base", "a?b")
        )) {
            var all = new ArrayList<>(List.of("--wxr", "x.xml"));
            all.addAll(args);
            var cmd = new CommandLine(new ImportCommand(null, null));
            cmd.setErr(new PrintWriter(new StringWriter()));
            assertThat(cmd.execute(all.toArray(String[]::new))).as(args.toString()).isEqualTo(2);
        }
    }
}
