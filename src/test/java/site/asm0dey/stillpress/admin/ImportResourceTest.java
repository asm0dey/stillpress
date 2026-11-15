package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.importer.AlreadyImportedException;
import site.asm0dey.stillpress.importer.ImportJob;
import site.asm0dey.stillpress.importer.ImportOptions;

@ExtendWith(MockitoExtension.class)
class ImportResourceTest {
    private static final ImportJob.Status RUNNING = new ImportJob.Status("running", 0, 0, null, null, false);
    private static final Path UPLOADED = Path.of("/data/import/export.xml");
    @Mock
    ImportJob job;
    @Mock
    FileUpload file;
    ImportResource resource;

    @BeforeEach
    void setUp() {
        resource = new ImportResource(job);
    }

    @Test
    void statusIsTheJobsStatus() {
        when(job.status()).thenReturn(RUNNING);
        assertThat(resource.status()).isEqualTo(RUNNING);
    }

    @Test
    void uploadStartsTheJobWithTheOptionsAndAnswers202WithItsStatus() throws Exception {
        when(file.uploadedFile()).thenReturn(UPLOADED);
        when(job.status()).thenReturn(RUNNING);
        try (var r = resource.upload(file, 12, 15, "topics", "labels")) {
            assertThat(r.getStatus()).isEqualTo(202);
            assertThat(r.getEntity()).isEqualTo(RUNNING);
        }
        verify(job).start(UPLOADED, new ImportOptions(12, "topics", "labels", 15));
    }

    @Test
    void noFileIs400() {
        try (var r = resource.upload(null, 10, 10, "category", "tag")) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "file is required"));
        }
        verifyNoInteractions(job);
    }

    @Test
    void invalidOptionsAre400WithTheOptionsMessageAndNothingStarts() throws Exception {
        try (var r = resource.upload(file, 0, 10, "category", "tag")) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "--posts-per-page must be a positive integer, got 0"));
        }
        try (var r = resource.upload(file, 10, 10, "a/b", "tag")) {
            assertThat(r.getStatus()).isEqualTo(400);
        }
        verify(job, org.mockito.Mockito.never()).start(any(), any());
    }

    @Test
    void anImportAlreadyDoneIs409AlreadyImportedWithItsMessage() throws Exception {
        when(file.uploadedFile()).thenReturn(UPLOADED);
        doThrow(new AlreadyImportedException("content exists")).when(job).start(any(), any());
        try (var r = resource.upload(file, 10, 10, "category", "tag")) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "already_imported", "message", "content exists"));
        }
    }
}
