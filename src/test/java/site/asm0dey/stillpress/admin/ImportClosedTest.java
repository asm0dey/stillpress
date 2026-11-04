package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Every admin write settles the site's origin: after it, an import is refused.
 */
@QuarkusTest
class ImportClosedTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    DSLContext db;
    long adminId;
    String admin;

    /**
     * Setup through the API (which may close the import), then the state is reopened and {@code write} runs.
     */
    record Write(Function<String, Object> setup, BiFunction<String, Object, Response> write) {}

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("title", "Blog");
        settings.put("timezone", "UTC");
        settings.put("home_url", "http://localhost:8081");
        adminId = users.create("admin", "admin@example.org", "admin", Role.ADMINISTRATOR).getId();
        users.setPassword(adminId, Passwords.hash("correct horse battery"));
        admin = sessions.create(adminId);
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    static Stream<Arguments> writes() {
        var map = new LinkedHashMap<String, Write>();
        map.put(
                "items POST",
                new Write(c -> null, (c, x) -> as(c).body(Map.of("type", "post")).post("/admin/api/items"))
        );
        map.put(
                "items PUT",
                new Write(c -> ItemsResourceTest.create(c, "post"), (c, x) -> ItemsResourceTest.update(
                        c,
                        (io.restassured.path.json.JsonPath) x,
                        Map.of("title", "T")
                ))
        );
        map.put(
                "items DELETE",
                new Write(c -> ItemsResourceTest.create(c, "post").getLong("id"), (c, x) -> as(c)
                    .delete("/admin/api/items/" + x))
        );
        map.put("items restore POST", new Write(c -> {
            var id = ItemsResourceTest.create(c, "post").getLong("id");
            as(c).delete("/admin/api/items/" + id).then().statusCode(204);
            return id;
        }, (c, x) -> as(c).post("/admin/api/items/" + x + "/restore")));
        map.put(
                "items autosave PUT",
                new Write(c -> ItemsResourceTest.create(c, "post").getLong("id"), (c, x) -> as(c)
                    .body(Map.of("title", "T", "body", "<p>x</p>", "excerpt", ""))
                    .put("/admin/api/items/" + x + "/autosave"))
        );
        map.put("items revision restore POST", new Write(c -> {
            var item = ItemsResourceTest.create(c, "post");
            ItemsResourceTest.update(c, item, Map.of("title", "First")).then().statusCode(200);
            var rid = as(c)
                .get("/admin/api/items/" + item.getLong("id") + "/revisions")
                .then()
                .extract()
                .jsonPath()
                .getLong("[0].id");
            return "/admin/api/items/" + item.getLong("id") + "/revisions/" + rid + "/restore";
        }, (c, x) -> as(c).post((String) x)));
        map.put(
                "blocks autosave PUT",
                new Write(c -> block(c).getLong("id"), (c, x) -> as(c)
                    .body(Map.of("title", "T", "content", "<p>y</p>"))
                    .put("/admin/api/blocks/" + x + "/autosave"))
        );
        map.put("blocks revision restore POST", new Write(c -> {
            var id = block(c).getLong("id");
            var rid = as(c)
                .get("/admin/api/blocks/" + id + "/revisions")
                .then()
                .extract()
                .jsonPath()
                .getLong("[0].id");
            return "/admin/api/blocks/" + id + "/revisions/" + rid + "/restore";
        }, (c, x) -> as(c).post((String) x)));
        map.put("media POST", new Write(c -> null, (c, x) -> upload(c)));
        map.put(
                "media PUT",
                new Write(c -> upload(c).then().statusCode(201).extract().path("id"), (c, x) -> as(c)
                    .body(Map.of("title", "T"))
                    .put("/admin/api/media/" + x))
        );
        map.put(
                "terms POST",
                new Write(c -> null, (c, x) -> as(c)
                    .body(Map.of("taxonomy", "category", "name", "News"))
                    .post("/admin/api/terms"))
        );
        map.put(
                "terms PUT",
                new Write(c -> TermsResourceTest.term(c, "category", "News", null), (c, x) -> as(c)
                    .body(Map.of("name", "Updates", "slug", "updates"))
                    .put("/admin/api/terms/" + x))
        );
        map.put(
                "terms DELETE",
                new Write(c -> TermsResourceTest.term(c, "post_tag", "Kids", null), (c, x) -> as(c)
                    .delete("/admin/api/terms/" + x))
        );
        map.put(
                "blocks POST",
                new Write(c -> null, (c, x) -> as(c)
                    .body(Map.of("title", "T", "content", "<p>x</p>"))
                    .post("/admin/api/blocks"))
        );
        map.put(
                "blocks PUT",
                new Write(c -> as(c)
                    .body(Map.of("title", "T", "content", "<p>x</p>"))
                    .post("/admin/api/blocks")
                    .then()
                    .statusCode(201)
                    .extract()
                    .jsonPath(), (c, x) -> {
                    var b = (io.restassured.path.json.JsonPath) x;
                    return as(c)
                        .body(Map.of(
                                "title",
                                "T",
                                "content",
                                "<p>y</p>",
                                "expectedModifiedAt",
                                b.getString("modifiedAt")
                        ))
                        .put("/admin/api/blocks/" + b.getLong("id"));
                })
        );
        map.put(
                "settings PUT",
                new Write(c -> as(c).get("/admin/api/settings").then().extract().jsonPath().getMap("$"), (c, x) -> as(c)
                    .body(x)
                    .put("/admin/api/settings"))
        );
        map.put(
                "redirects POST",
                new Write(c -> null, (c, x) -> as(c)
                    .body(Map.of("from", "/old/", "to", "/new/", "status", 301))
                    .post("/admin/api/redirects"))
        );
        map.put(
                "redirects DELETE",
                new Write(c -> as(c)
                    .body(Map.of("from", "/old/", "to", "/new/", "status", 301))
                    .post("/admin/api/redirects")
                    .then()
                    .statusCode(201), (c, x) -> as(c).queryParam("from", "/old/").delete("/admin/api/redirects"))
        );
        map.put(
                "users POST",
                new Write(c -> null, (c, x) -> as(c)
                    .body(Map.of("login", "ed", "email", "ed@example.org", "displayName", "Ed", "role", "editor"))
                    .post("/admin/api/users"))
        );
        map.put(
                "users PUT",
                new Write(c -> newUser(c), (c, x) -> as(c)
                    .body(Map.of("displayName", "Edward", "email", "ed@example.org"))
                    .put("/admin/api/users/" + x))
        );
        map.put(
                "users role PUT",
                new Write(c -> newUser(c), (c, x) -> as(c)
                    .body(Map.of("role", "author"))
                    .put("/admin/api/users/" + x + "/role"))
        );
        map.put(
                "users password-link POST",
                new Write(c -> newUser(c), (c, x) -> as(c).post("/admin/api/users/" + x + "/password-link"))
        );
        return map
            .entrySet()
            .stream()
            .map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void everyWritePathClosesImport(String name, Write w) {
        var fixture = w.setup().apply(admin);
        settings.delete(ImportGuard.STATE);
        assertThat(w.write().apply(admin, fixture).statusCode()).as(name).isBetween(200, 299);
        assertThat(settings.get(ImportGuard.STATE)).as(name).hasValue("closed");
        importUpload().then().statusCode(409).body("error", equalTo("already_imported"));
    }

    /**
     * Ruling R30: the admin language is the user's preference, not site content, so choosing it is the one
     * authenticated write that leaves the import open.
     */
    @Test
    void choosingTheAdminLanguageLeavesImportOpen() {
        as(admin).body(Map.of("locale", "ru")).put("/admin/api/session/locale").then().statusCode(204);
        assertThat(settings.get(ImportGuard.STATE)).isNotPresent();
        as(admin).get("/admin/api/site").then().body("importAllowed", equalTo(true));
    }

    @Test
    void rejectedWriteLeavesImportOpen() {
        as(admin)
            .body(Map.of("from", "no-slash", "to", "/new/", "status", 301))
            .post("/admin/api/redirects")
            .then()
            .statusCode(400);
        assertThat(settings.get(ImportGuard.STATE)).isNotPresent();
        as(admin).get("/admin/api/site").then().body("importAllowed", equalTo(true));
    }

    private Response importUpload() {
        return csrf()
            .cookie("sp_session", admin)
            .multiPart("file", "export.xml", "<rss/>".getBytes(StandardCharsets.UTF_8), "text/xml")
            .post("/admin/api/import");
    }

    @Test
    void writeRefusedByExceptionLeavesImportOpen() {
        var contributor = sessions.create(users.create("c", "c@example.org", "C", Role.CONTRIBUTOR).getId());
        as(contributor).body(Map.of("taxonomy", "category", "name", "News")).post("/admin/api/terms").then().statusCode(
                403
        );
        assertThat(settings.get(ImportGuard.STATE)).isNotPresent();
        as(admin).get("/admin/api/site").then().body("importAllowed", equalTo(true));
    }

    private static io.restassured.path.json.JsonPath block(String cookie) {
        return as(cookie)
            .body(Map.of("title", "T", "content", "<p>x</p>"))
            .post("/admin/api/blocks")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    }

    private static long newUser(String cookie) {
        return as(cookie)
            .body(Map.of("login", "ed", "email", "ed@example.org", "displayName", "Ed", "role", "editor"))
            .post("/admin/api/users")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    }

    private static Response upload(String cookie) {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
            return csrf()
                .cookie("sp_session", cookie)
                .multiPart("file", "p.png", out.toByteArray(), "application/octet-stream")
                .post("/admin/api/media");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }
}
