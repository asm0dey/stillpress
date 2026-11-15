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
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class SettingsResourceQuarkusTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    RedirectRepository redirects;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    String admin;
    String editor;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("timezone", "Europe/Moscow");
        settings.put("title", "Blog");
        settings.put("language", "en-GB");
        settings.put("home_url", "http://localhost:8081");
        admin = cookie("admin", Role.ADMINISTRATOR);
        editor = cookie("editor", Role.EDITOR);
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void getReturnsDefaultsOnAFreshStore() {
        as(admin)
            .get("/admin/api/settings")
            .then()
            .statusCode(200)
            .body("postsPerPage", equalTo(10))
            .body("postsPerRss", equalTo(10))
            .body("permalinkStructure", equalTo("/%year%/%monthnum%/%day%/%postname%/"))
            .body("categoryBase", equalTo("category"))
            .body("tagBase", equalTo("tag"))
            .body("defaultCommentStatus", equalTo("open"))
            .body("timezone", equalTo("Europe/Moscow"));
    }

    @Test
    void titleChangeRepublishesTheWholeSite() {
        var body = current();
        body.put("title", "New name");
        put(admin, body).then().statusCode(200).body("title", equalTo("New name"));
        var index = dataDir.current().resolve("index.html");
        TestStore.await(() -> contains(index, "New name"), 30);
        assertThat(index).content().contains("New name");
    }

    @Test
    void permalinkStructureChangeKeepsExistingPermalinks() {
        var a = publish("A");
        var day = LocalDate.now(ZoneId.of("Europe/Moscow")).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(a.getString("permalink")).isEqualTo("/" + day + "/a/");
        var body = current();
        body.put("permalinkStructure", "/%postname%/");
        put(admin, body).then().statusCode(200);
        var b = publish("B");
        assertThat(b.getString("permalink")).isEqualTo("/b/");
        assertThat(ItemsResourceQuarkusTest.get(admin, a.getLong("id")).getString("permalink")).isEqualTo(
                "/" + day + "/a/"
        );
    }

    @Test
    void rejectedSaveLeavesImportOpen() {
        var body = current();
        body.put("postsPerPage", 0);
        put(admin, body).then().statusCode(400);
        as(admin).get("/admin/api/site").then().body("importAllowed", org.hamcrest.Matchers.is(true));
        assertThat(settings.get("import_state")).isNotPresent();
    }

    @Test
    void siteShowsZoneAndStructureToAnyUser() {
        settings.put("permalink_structure", "/%postname%/");
        as(editor)
            .get("/admin/api/site")
            .then()
            .statusCode(200)
            .body("timezone", equalTo("Europe/Moscow"))
            .body("permalinkStructure", equalTo("/%postname%/"));
    }

    @Test
    void editorIs403() {
        as(editor).get("/admin/api/settings").then().statusCode(403);
        put(editor, current()).then().statusCode(403);
    }

    @Test
    void baseChangeRedirectsEveryTermArchive() {
        var parent = term("Travel", null);
        term("Europe", parent);
        var body = current();
        body.put("categoryBase", "topics");
        put(admin, body).then().statusCode(200);
        assertThat(redirects.find("/category/travel/").orElseThrow().to()).isEqualTo("/topics/travel/");
        assertThat(redirects.find("/category/travel/europe/").orElseThrow().to()).isEqualTo("/topics/travel/europe/");
        assertThat(redirects.find("/category/").orElseThrow().to()).isEqualTo("/topics/");
        var index = dataDir.current().resolve("topics/travel/index.html");
        TestStore.await(() -> Files.exists(index), 30);
        assertThat(index).exists();
    }

    @Test
    void settingsWriteClosesImport() {
        put(admin, current()).then().statusCode(200);
        assertThat(settings.get("import_state")).hasValue("closed");
    }

    private long term(String name, Long parent) {
        var body = new HashMap<String, Object>(Map.of("taxonomy", "category", "name", name));
        if (parent != null) {
            body.put("parentId", parent);
        }
        return as(admin)
            .body(body)
            .post("/admin/api/terms")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    }

    private io.restassured.path.json.JsonPath publish(String title) {
        var item = ItemsResourceQuarkusTest.create(admin, "post");
        return ItemsResourceQuarkusTest
            .update(admin, item, Map.of("title", title, "status", "publish"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    }

    private Map<String, Object> current() {
        return new HashMap<>(as(admin)
            .get("/admin/api/settings")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getMap("$")
        );
    }

    private static Response put(String cookie, Map<String, Object> body) {
        return as(cookie).body(body).put("/admin/api/settings");
    }

    private static boolean contains(Path page, String text) {
        try {
            return Files.exists(page) && Files.readString(page).contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    private String cookie(String login, Role role) {
        var id = users.create(login, login + "@example.org", login, role).id();
        users.setPassword(id, TestStore.PASSWORD_HASH);
        return sessions.create(id);
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }

    @Test
    void anonymousIs401() {
        AuthMatrix.anonymousIs401("GET", "/admin/api/settings");
        AuthMatrix.anonymousIs401("PUT", "/admin/api/settings");
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        var body = current();
        body.put("title", "Changed");
        // the same body with a token is a 200 (titleChangeRepublishesTheWholeSite)
        AuthMatrix.missingCsrfIs400(admin, "PUT", "/admin/api/settings", body);
        assertThat(settings.get("title")).hasValue("Blog");
    }
}
