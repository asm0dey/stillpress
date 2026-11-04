package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.importer.FakeMediaFetcher;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
@TestProfile(ImportMediaLimitTest.NoMedia.class)
class ImportMediaLimitTest {
    public static class NoMedia implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("stillpress.import.media-limit", "0");
        }
    }

    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    FakeMediaFetcher fetcher;

    @Test
    void configuredLimitCapsAdminImportDownloads() throws Exception {
        store.reset();
        var id = users.create("admin", "admin@example.org", "admin", Role.ADMINISTRATOR).getId();
        users.setPassword(id, Passwords.hash("correct horse battery"));
        var req = csrf().cookie("sp_session", sessions.create(id));
        try (var in = TestStore.sample()) {
            req
                .multiPart("file", "export.xml", in.readAllBytes(), "text/xml")
                .multiPart("postsPerPage", "10")
                .multiPart("postsPerRss", "10")
                .multiPart("categoryBase", "category")
                .multiPart("tagBase", "tag")
                .post("/admin/api/import")
                .then()
                .statusCode(202);
        }
        var deadline = Instant.now().plusSeconds(30);
        String state;
        while ((state = req.get("/admin/api/import").path("state")).equals("running")) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(100);
        }
        assertThat(state).isEqualTo("done");
        assertThat(fetcher.fetched()).isEmpty();
        req.get("/admin/api/import").then().body("report", containsString("Skipped media (limit 0)"));
        fetcher.reset();
    }
}
