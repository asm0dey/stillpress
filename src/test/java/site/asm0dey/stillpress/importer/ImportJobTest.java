package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.ItemRepository;

@ExtendWith(MockitoExtension.class)
class ImportJobTest {
    @Mock
    ImportGuard guard;
    @Mock
    WordPressImporter importer;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    ItemRepository store;
    @TempDir
    Path tmp;

    @Test
    void configuredLimitCapsAdminImportDownloads() throws Exception {
        var skipped = List.of("https://example.org/wp-content/uploads/a.jpg");
        var report =
                new ImportReport(1, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), skipped, List.of());
        when(importer.importWxr(any(), eq(0), eq(ImportOptions.DEFAULTS), any())).thenReturn(report);
        when(guard.state()).thenReturn(Optional.of("done"));
        var job = new ImportJob(guard, importer, queue, jobs, new DataDir(tmp.toString()), store, Optional.of(0));
        var wxr = Files.writeString(tmp.resolve("export.xml"), "<rss/>");

        job.start(wxr, ImportOptions.DEFAULTS);

        var deadline = Instant.now().plusSeconds(10);
        while (job.status().report() == null) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(20);
        }
        verify(importer).importWxr(any(), eq(0), eq(ImportOptions.DEFAULTS), any());
        assertThat(job.status())
            .extracting(ImportJob.Status::state, ImportJob.Status::report)
            .containsExactly("done", report.toText(0));
        assertThat(job.status().report()).contains("Skipped media (limit 0): 1");
    }
}
