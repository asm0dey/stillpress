package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.MEDIA;
import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class MediaServiceTest {
    @Inject
    TestStore store;
    @Inject
    MediaService service;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    long userId;

    @BeforeEach
    void reset() {
        store.reset();
        userId = users.create("a", "a@example.org", "A", Role.AUTHOR).getId();
    }

    @Test
    void nameIsReducedSluggedAndLowerCased() throws IOException {
        var r = service.store("../../Some Dir\\My Photo.PNG", tmp(), userId);
        assertThat(r.getPath()).endsWith("/my-photo.png");
        assertThat(r.getMimeType()).isEqualTo("image/png");
        assertThat(r.getUploadedBy()).isEqualTo(userId);
        assertThat(r.getSizeBytes()).isEqualTo(3L);
        assertThat(db.fetchCount(PUBLISH_QUEUE, PUBLISH_QUEUE.KIND.eq("media"))).isEqualTo(1);
    }

    @Test
    void folderFollowsSiteTimeZone() throws IOException {
        settings.put("timezone", "Pacific/Kiritimati");
        var month = YearMonth.now(ZoneId.of("Pacific/Kiritimati"));
        var r = service.store("a.jpg", tmp(), userId);
        assertThat(r.getPath()).isEqualTo("/media/%d/%02d/a.jpg".formatted(month.getYear(), month.getMonthValue()));
        assertThat(r.getWidth()).isNull();
    }

    @Test
    void disallowedExtensionIsRefusedAndLeavesNothing() throws IOException {
        for (var name : List.of("x.svg", "x.HTML", "x.js", "x.xml", "x")) {
            var f = tmp();
            assertThatThrownBy(() -> service.store(name, f, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type_not_allowed");
        }
        assertThat(db.fetchCount(MEDIA)).isZero();
    }

    @Test
    void rolledBackUploadLeavesNoFile() throws IOException {
        var month = YearMonth.now(ZoneOffset.UTC);
        var url = "/media/%d/%02d/dup.png".formatted(month.getYear(), month.getMonthValue());
        db.insertInto(MEDIA, MEDIA.SOURCE_URL, MEDIA.PATH).values(url, url).execute();
        var f = tmp();
        assertThatThrownBy(() -> service.store("dup.png", f, userId)).isInstanceOf(RuntimeException.class);
        try (var files = Files.walk(dataDir.media())) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private Path tmp() throws IOException {
        var f = Files.createTempFile(dataDir.root(), "upload", ".tmp");
        Files.write(f, new byte[] {1, 2, 3});
        return f;
    }
}
