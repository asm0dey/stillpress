package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.test.junit.QuarkusTest;
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
import site.asm0dey.stillpress.store.TermRepository;

@QuarkusTest
class TermsResourceQuarkusTest {
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
    TermRepository terms;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    String admin;
    String author;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("timezone", "UTC");
        settings.put("title", "Blog");
        settings.put("home_url", "http://localhost:8081");
        admin = cookie("admin", Role.ADMINISTRATOR);
        author = cookie("writer", Role.AUTHOR);
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void renameSlugMovesArchiveAndRedirectsOldOne() {
        var travel = term(admin, "category", "Travel", null);
        var europe = term(admin, "category", "Europe", travel);
        publish("Paris", europe);
        var old = dataDir.current().resolve("category/travel/europe/index.html");
        TestStore.await(() -> Files.exists(old), 30);
        assertThat(old).exists();
        put(admin, travel, Map.of("name", "Trips", "slug", "trips")).then().statusCode(200).body(
                "slug",
                equalTo("trips")
        );
        var moved = dataDir.current().resolve("category/trips/europe/index.html");
        TestStore.await(() -> Files.exists(moved) && !Files.exists(dataDir.current().resolve("category/travel")), 30);
        assertThat(moved).exists();
        assertThat(dataDir.current().resolve("category/travel")).doesNotExist();
        assertThat(redirects.find("/category/travel/").orElseThrow().to()).isEqualTo("/category/trips/");
        assertThat(redirects.find("/category/travel/europe/").orElseThrow().to()).isEqualTo("/category/trips/europe/");
    }

    @Test
    void slugTakenIs409() {
        term(admin, "category", "Travel", null);
        var news = term(admin, "category", "News", null);
        put(admin, news, Map.of("name", "News", "slug", "travel"))
            .then()
            .statusCode(409)
            .body("error", equalTo("slug_taken"));
    }

    @Test
    void listHasCountAndDescription() {
        var travel = term(admin, "category", "Travel", null);
        put(admin, travel, Map.of("name", "Travel", "slug", "travel", "description", "Trips abroad")).then().statusCode(
                200
        );
        publish("Paris", travel);
        as(admin)
            .get("/admin/api/terms?taxonomy=category")
            .then()
            .statusCode(200)
            .body("find { it.slug == 'travel' }.count", equalTo(1))
            .body("find { it.slug == 'travel' }.description", equalTo("Trips abroad"));
    }

    @Test
    void deleteMovesPostsToDefaultCategoryAndChildrenUp() {
        var news = term(admin, "category", "News", null);
        settings.put("default_category", String.valueOf(news));
        var travel = term(admin, "category", "Travel", null);
        var europe = term(admin, "category", "Europe", travel);
        var post = publish("Rome", travel);
        as(admin).delete("/admin/api/terms/" + travel).then().statusCode(204);
        assertThat(terms.findById(travel)).isEmpty();
        assertThat(terms.findById(europe).orElseThrow().parentId()).isNull();
        assertThat(terms.idsForItem(post, "category")).containsExactly(news);
        assertThat(redirects.find("/category/travel/europe/").orElseThrow().to()).isEqualTo("/category/europe/");
        var europePage = dataDir.current().resolve("category/europe/index.html");
        TestStore.await(() -> Files.exists(europePage), 30);
        assertThat(europePage).exists();
    }

    @Test
    void nameLongerThanTheColumnIs400() {
        var longName = "n".repeat(201);
        as(admin)
            .body(Map.of("taxonomy", "category", "name", longName))
            .post("/admin/api/terms")
            .then()
            .statusCode(400)
            .body("field", equalTo("name"));
        var news = term(admin, "category", "News", null);
        put(admin, news, Map.of("name", longName, "slug", "news")).then().statusCode(400).body("field", equalTo("name"));
        assertThat(terms.findById(news).orElseThrow().name()).isEqualTo("News");
        term(admin, "category", "n".repeat(200), null);
    }

    @Test
    void authorMayCreateButAnonymousIs401AndOnlyEditorsEditOrDelete() {
        var contributor = cookie("contrib", Role.CONTRIBUTOR);
        var tag = term(author, "post_tag", "Kids", null);
        AuthMatrix.forbiddenFor(contributor, "POST", "/admin/api/terms");
        for (var call :
                List.of(
                        List.of("GET", "/admin/api/terms"),
                        List.of("POST", "/admin/api/terms"),
                        List.of("PUT", "/admin/api/terms/" + tag),
                        List.of("DELETE", "/admin/api/terms/" + tag)
        )) {
            AuthMatrix.anonymousIs401(call.get(0), call.get(1));
        }
        AuthMatrix.forbiddenFor(author, "PUT", "/admin/api/terms/" + tag);
        AuthMatrix.forbiddenFor(author, "DELETE", "/admin/api/terms/" + tag);
        assertThat(terms.findById(tag).orElseThrow().slug()).isEqualTo("kids");
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        // with a token this body is a 201 (term helper)
        AuthMatrix.missingCsrfIs400(admin, "POST", "/admin/api/terms", Map.of("taxonomy", "category", "name", "Travel"));
        assertThat(terms.byTaxonomy("category"))
            .extracting(t -> t.name())
            .doesNotContain("Travel");
        AuthMatrix.missingCsrfIs400(admin, "PUT", "/admin/api/terms/1", Map.of("name", "Trips", "slug", "trips"));
        AuthMatrix.missingCsrfIs400(admin, "DELETE", "/admin/api/terms/1");
    }

    private long publish(String title, long category) {
        var item = ItemsResourceQuarkusTest.create(admin, "post");
        ItemsResourceQuarkusTest
            .update(admin, item, Map.of("title", title, "status", "publish", "categories", List.of(category)))
            .then()
            .statusCode(200);
        return item.getLong("id");
    }

    static long term(String cookie, String taxonomy, String name, Long parent) {
        var body = new HashMap<String, Object>(Map.of("taxonomy", taxonomy, "name", name));
        if (parent != null) {
            body.put("parentId", parent);
        }
        return as(cookie)
            .body(body)
            .post("/admin/api/terms")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    }

    private static io.restassured.response.Response put(String cookie, long id, Map<String, Object> body) {
        return as(cookie).body(body).put("/admin/api/terms/" + id);
    }

    private String cookie(String login, Role role) {
        var id = users.create(login, login + "@example.org", login, role).id();
        users.setPassword(id, TestStore.PASSWORD_HASH);
        return sessions.create(id);
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }
}
