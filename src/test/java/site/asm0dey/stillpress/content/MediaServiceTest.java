package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.db.tables.pojos.Media;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.MediaRepository;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {
    private static final long USER = 7;
    @TempDir
    Path root;
    @Mock
    jakarta.transaction.TransactionSynchronizationRegistry registry;
    @Mock
    MediaRepository media;
    @Mock
    Permalinker permalinker;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    ImportGuard guard;
    DataDir dataDir;
    MediaService service;

    @BeforeEach
    void setUp() {
        dataDir = new DataDir(root.toString());
        service = new MediaService(registry, media, dataDir, permalinker, queue, jobs, guard);
    }

    private void insertEchoes() {
        when(media.insert(any(), any(), any(), any(), anyLong(), anyLong(), any()))
            .thenAnswer(i -> new Media(
                    1L,
                    null,
                    i.getArgument(0),
                    i.getArgument(0),
                    i.getArgument(1),
                    null,
                    null,
                    null,
                    i.getArgument(2),
                    i.getArgument(3),
                    i.getArgument(4),
                    i.getArgument(6),
                    i.getArgument(5)
            ));
    }

    @Test
    void nameIsReducedSluggedAndLowerCased() throws IOException {
        when(permalinker.zone()).thenReturn(ZoneOffset.UTC);
        insertEchoes();
        var r = service.store("../../Some Dir\\My Photo.PNG", tmp(), USER);
        assertThat(r.path()).endsWith("/my-photo.png");
        assertThat(r.mimeType()).isEqualTo("image/png");
        assertThat(r.uploadedBy()).isEqualTo(USER);
        assertThat(r.sizeBytes()).isEqualTo(3L);
        verify(queue).media();
    }

    @Test
    void folderFollowsSiteTimeZone() throws IOException {
        var zone = ZoneId.of("Pacific/Kiritimati");
        when(permalinker.zone()).thenReturn(zone);
        insertEchoes();
        var month = YearMonth.now(zone);
        var r = service.store("a.jpg", tmp(), USER);
        assertThat(r.path()).isEqualTo("/media/%d/%02d/a.jpg".formatted(month.getYear(), month.getMonthValue()));
        assertThat(r.width()).isNull();
    }

    @Test
    void disallowedExtensionIsRefusedAndLeavesNothing() throws IOException {
        for (var name : List.of("x.svg", "x.HTML", "x.js", "x.xml", "x")) {
            var f = tmp();
            assertThatThrownBy(() -> service.store(name, f, USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type_not_allowed");
        }
        verifyNoInteractions(media);
    }

    private Path tmp() throws IOException {
        var f = Files.createTempFile(dataDir.root(), "upload", ".tmp");
        Files.write(f, new byte[] {1, 2, 3});
        return f;
    }
}
