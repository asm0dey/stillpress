package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@QuarkusTest
class FromScratchBlogTest {
    @Inject
    TestStore store;
    @Inject
    SetupToken token;
    @Inject
    SettingRepository settings;
    @Inject
    TermRepository terms;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;

    @BeforeEach
    void reset() {
        store.reset();
        token.regenerate();
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void startEmptyThenFirstPostMakesAWorkingBlog() throws IOException {
        var cookie = setup();
        csrf().cookie("sp_session", cookie).post("/admin/api/setup/start-empty").then().statusCode(204);
        var words = IntStream
            .rangeClosed(1, 100)
            .mapToObj(i -> "слово" + i)
            .collect(Collectors.joining(" "));
        var item = ItemsResourceTest.create(cookie, "post");
        var saved = ItemsResourceTest
            .update(
                    cookie,
                    item,
                    Map.of(
                            "title",
                            "Первый пост",
                            "body",
                            "<!-- wp:paragraph --><p>" + words + "</p><!-- /wp:paragraph -->",
                            "status",
                            "publish"
                    )
            )
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        var home = dataDir.current().resolve("index.html");
        TestStore.await(() -> contains(home, "Первый пост"), 30);
        assertThat(home).content().contains("Первый пост");
        var category = dataDir.current().resolve("category/без-рубрики/index.html");
        TestStore.await(() -> contains(category, "Первый пост"), 30);
        assertThat(category).content().contains("Первый пост");
        var guid = db.select(ITEM.GUID).from(ITEM).where(ITEM.ID.eq(saved.getLong("id"))).fetchOne(ITEM.GUID);
        var feed = dataDir.current().resolve("feed/index.html");
        TestStore.await(() -> contains(feed, guid), 30);
        assertThat(feed).content().contains(guid);
        var slug = db.select(AUTHOR.SLUG).from(AUTHOR).where(AUTHOR.LOGIN.eq("admin0")).fetchOne(AUTHOR.SLUG);
        var author = dataDir.current().resolve("author/" + slug + "/index.html");
        TestStore.await(() -> contains(author, "Первый пост"), 30);
        assertThat(author).content().contains("Первый пост");
        var excerpt =
                Pattern.compile("<div class=\"excerpt\">(.*?)</div>", Pattern.DOTALL).matcher(Files.readString(home));
        assertThat(excerpt.find()).isTrue();
        var text = excerpt.group(1).strip();
        assertThat(text).endsWith(" […]");
        assertThat(text.substring(0, text.length() - " […]".length()).split(" "))
            .hasSize(55)
            .startsWith("слово1", "слово2")
            .endsWith("слово55");
        assertThat(terms.findBySlug("category", "без-рубрики")).isPresent();
        assertThat(settings.get("default_category")).isPresent();
    }

    @Test
    void importAfterSetupStillAllowed() {
        var cookie = setup();
        csrf().cookie("sp_session", cookie).get("/admin/api/site").then().body("importAllowed", is(true));
        assertThat(terms.all()).isEmpty();
    }

    @Test
    void firstPostClosesImport() {
        var cookie = setup();
        var item = ItemsResourceTest.create(cookie, "post");
        ItemsResourceTest.update(cookie, item, Map.of("title", "First", "status", "publish")).then().statusCode(200);
        upload(cookie).then().statusCode(409);
        csrf().cookie("sp_session", cookie).get("/admin/api/site").then().body("importAllowed", is(false));
    }

    @Test
    void englishSiteGetsUncategorized() {
        var cookie = setup("en-GB");
        var item = ItemsResourceTest.create(cookie, "post");
        ItemsResourceTest.update(cookie, item, Map.of("title", "First", "status", "publish")).then().statusCode(200);
        assertThat(terms.findBySlug("category", "uncategorized")).isPresent();
    }

    @Test
    void postWithACategoryGetsNoDefault() {
        var cookie = setup("en-GB");
        var cat = csrf()
            .contentType(JSON)
            .cookie("sp_session", cookie)
            .body(Map.of("taxonomy", "category", "name", "News"))
            .post("/admin/api/terms")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
        var item = ItemsResourceTest.create(cookie, "post");
        ItemsResourceTest
            .update(cookie, item, Map.of("title", "First", "status", "publish", "categories", List.of(cat)))
            .then()
            .statusCode(200);
        assertThat(terms.findBySlug("category", "uncategorized")).isNotPresent();
    }

    @Test
    void permalinkLoopStopsAfterAThousandAttempts() {
        var cookie = setup("en-GB");
        // validation refuses this structure; a store written by hand or an older version may still hold it
        settings.put("permalink_structure", "/fixed/");
        var first = ItemsResourceTest.create(cookie, "post");
        ItemsResourceTest.update(cookie, first, Map.of("title", "One", "status", "publish")).then().statusCode(200);
        var second = ItemsResourceTest.create(cookie, "post");
        ItemsResourceTest.update(cookie, second, Map.of("title", "Two", "status", "publish")).then().statusCode(500);
        assertThat(ItemsResourceTest.get(cookie, second.getLong("id")).getString("status")).isEqualTo("draft");
    }

    private String setup() {
        return setup("ru-RU");
    }

    private String setup(String language) {
        return csrf()
            .contentType(JSON)
            .body(Map.of(
                    "token",
                    token.current().orElseThrow(),
                    "title",
                    "Блог",
                    "language",
                    language,
                    "timezone",
                    "Europe/Moscow",
                    "login",
                    "admin0",
                    "email",
                    "admin0@example.org",
                    "displayName",
                    "Admin",
                    "password",
                    "correct horse battery"
            ))
            .post("/admin/api/setup")
            .then()
            .statusCode(201)
            .extract()
            .cookie("sp_session");
    }

    private static Response upload(String cookie) {
        try (var in = TestStore.sample()) {
            return csrf()
                .cookie("sp_session", cookie)
                .multiPart("file", "export.xml", in.readAllBytes(), "text/xml")
                .post("/admin/api/import");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean contains(Path page, String text) {
        try {
            return Files.exists(page) && Files.readString(page).contains(text);
        } catch (IOException e) {
            return false;
        }
    }
}
