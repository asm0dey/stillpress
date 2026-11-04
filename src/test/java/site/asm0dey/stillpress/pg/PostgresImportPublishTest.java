package site.asm0dey.stillpress.pg;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.publish.SitePublisher;
import site.asm0dey.stillpress.store.RedirectRepository;

@QuarkusTest
@TestProfile(PostgresProfile.class)
class PostgresImportPublishTest {
    @Inject
    TestStore store;
    @Inject
    SitePublisher publisher;
    @Inject
    RedirectRepository redirects;

    @Test
    void sampleImportsAndPublishes() throws Exception {
        store.reset();
        var report = store.importSample();
        assertThat(report.items()).isEqualTo(9);
        var rel = publisher.publishAll().release();
        assertThat(rel.resolve("2023/01/14/месяц-ноябрь-год-2022/index.html")).exists();
        assertThat(rel.resolve("category/travel/europe/index.html")).exists();
        assertThat(rel.resolve("feed/index.html"))
            .content()
            .contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994</guid>");
        assertThat(redirects.find("?p=5994").orElseThrow().getToPath()).isEqualTo("/2023/01/14/месяц-ноябрь-год-2022/");
    }
}
