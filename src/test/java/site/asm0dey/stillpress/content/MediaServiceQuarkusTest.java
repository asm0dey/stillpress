package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.MEDIA;
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

@QuarkusTest
class MediaServiceQuarkusTest {
    @Inject
    TestStore store;
    @Inject
    MediaService service;
    @Inject
    AuthorRepository users;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    long userId;

    @BeforeEach
    void reset() {
        store.reset();
        userId = users.create("a", "a@example.org", "A", Role.AUTHOR).id();
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
