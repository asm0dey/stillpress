package site.asm0dey.stillpress.admin;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.importer.FakeMediaFetcher;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ImportResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    DataDir dataDir;
    @Inject
    SettingRepository settings;
    @Inject
    FakeMediaFetcher fetcher;
    String adminCookie;
    String writerCookie;

    @BeforeEach
    void reset() {
        store.reset();
        adminCookie = sessions.create(user("admin", Role.ADMINISTRATOR));
        writerCookie = sessions.create(user("writer", Role.AUTHOR));
    }

    @AfterEach
    void settle() {
        // the next test's reset must not race a background import
        poll(s -> !s.getString("state").equals("running") || s.getString("error") != null);
        fetcher.reset();
    }

    @Test
    void uploadImportsAndPublishesInBackground() {
        upload(sampleBytes()).then().statusCode(202).body("state", equalTo("running"));
        awaitStatus("done"::equals);
        as(adminCookie)
            .get("/admin/api/import")
            .then()
            .body("items", equalTo(7))
            .body("report", containsString("Imported 7"));
        assertThat(dataDir.current().resolve("2023/01/14/месяц-ноябрь-год-2022/index.html")).exists();
        try (var left = Files.list(dataDir.root().resolve("import"))) {
            assertThat(left).as("uploaded copy deleted").isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void rejectedUploadLeavesTheRunningImportAlone() {
        var gate = fetcher.hold();
        try {
            upload(sampleBytes()).then().statusCode(202);
            upload(sampleBytes()).then().statusCode(409);
            as(adminCookie).get("/admin/api/import").then().body("state", equalTo("running")).body("error", nullValue());
        } finally {
            gate.countDown();
        }
        awaitStatus("done"::equals);
    }

    @Test
    void secondUploadIs409() {
        upload(sampleBytes()).then().statusCode(202);
        awaitStatus("done"::equals);
        upload(sampleBytes()).then().statusCode(409).body("error", equalTo("already_imported"));
    }

    @Test
    void uploadLargerThanDefaultBodyLimitIsAccepted() {
        var xml = new String(sampleBytes(), UTF_8);
        var big = xml.replace("</rss>", "<!--" + "x".repeat(11 * 1024 * 1024) + "-->\n</rss>");
        upload(big.getBytes(UTF_8)).then().statusCode(202);
        awaitStatus("done"::equals);
    }

    @Test
    void malformedFileFailsAndAllowsRetry() {
        upload("<rss><not-closed>".getBytes(UTF_8)).then().statusCode(202);
        awaitStatus("failed"::equals);
        as(adminCookie)
            .get("/admin/api/import")
            .then()
            .body("error", containsString("Invalid WXR"))
            .body("contentLeft", equalTo(false));
        upload(sampleBytes()).then().statusCode(202);
        awaitStatus("done"::equals);
    }

    @Test
    void errorDuringImportMarksItFailed() {
        fetcher.errorOn("/2023/01/a.jpg");
        upload(sampleBytes()).then().statusCode(202);
        awaitStatus("failed"::equals);
        as(adminCookie)
            .get("/admin/api/import")
            .then()
            .body("error", containsString("fetcher error"))
            .body("contentLeft", equalTo(false));
    }

    @Test
    void runningStateWithoutAJobHereSaysSo() {
        settings.put(ImportGuard.STATE, "running");
        as(adminCookie)
            .get("/admin/api/import")
            .then()
            .body("state", equalTo("running"))
            .body("error", containsString("No import is running in this server"));
    }

    @Test
    void contentLeftComesFromTheStore() throws Exception {
        // as after a restart: a failure persisted, no report in memory, content in the store
        store.importSample();
        settings.put(ImportGuard.STATE, "failed");
        as(adminCookie).get("/admin/api/import").then().body("state", equalTo("failed")).body(
                "contentLeft",
                equalTo(true)
        );
        upload(sampleBytes()).then().statusCode(409);
    }

    @Test
    void invalidOptionsAre400() {
        as(adminCookie)
            .multiPart("file", "export.xml", sampleBytes(), "text/xml")
            .multiPart("postsPerPage", "0")
            .post("/admin/api/import")
            .then()
            .statusCode(400)
            .body("error", containsString("--posts-per-page"));
        as(adminCookie).get("/admin/api/import").then().body("state", equalTo("idle"));
    }

    @Test
    void nonAdministratorIs403() {
        as(writerCookie)
            .multiPart("file", "export.xml", sampleBytes(), "text/xml")
            .post("/admin/api/import")
            .then()
            .statusCode(403);
        as(writerCookie).get("/admin/api/import").then().statusCode(403);
    }

    private Response upload(byte[] bytes) {
        return as(adminCookie)
            .multiPart("file", "export.xml", bytes, "text/xml")
            .multiPart("postsPerPage", "10")
            .multiPart("postsPerRss", "10")
            .multiPart("categoryBase", "category")
            .multiPart("tagBase", "tag")
            .post("/admin/api/import");
    }

    private void awaitStatus(Predicate<String> wanted) {
        poll(s -> wanted.test(s.getString("state")));
    }

    private void poll(Predicate<JsonPath> wanted) {
        var deadline = Instant.now().plusSeconds(30);
        JsonPath status;
        while (!wanted.test(status = as(adminCookie).get("/admin/api/import").then().extract().jsonPath())) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("import still " + status.prettify() + " after 30 s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private long user(String login, Role role) {
        var id = users.create(login, login + "@example.org", login, role).getId();
        users.setPassword(id, Passwords.hash("correct horse battery"));
        return id;
    }

    private static RequestSpecification as(String cookie) {
        return given().header("X-Requested-With", "stillpress").cookie("sp_session", cookie);
    }

    private static byte[] sampleBytes() {
        try (var in = TestStore.sample()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
