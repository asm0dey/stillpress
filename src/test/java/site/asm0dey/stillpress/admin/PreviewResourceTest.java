package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
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
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
class PreviewResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    String author1;
    String author2;

    @BeforeEach
    void reset() {
        store.reset();
        author1 = sessions.create(users.create("author1", "a1@example.org", "A1", Role.AUTHOR).getId());
        author2 = sessions.create(users.create("author2", "a2@example.org", "A2", Role.AUTHOR).getId());
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void previewShowsAutosaveAndWritesNothing() throws Exception {
        var published = ItemsResourceTest
            .update(
                    author1,
                    ItemsResourceTest.create(author1, "post"),
                    Map.of("title", "Old title", "status", "publish")
            )
            .jsonPath();
        var page = dataDir.current().resolve(published.getString("permalink").substring(1)).resolve("index.html");
        TestStore.await(() -> Files.exists(page), 30);
        var id = published.getLong("id");
        csrf()
            .contentType(JSON)
            .cookie("sp_session", author1)
            .body(Map.of(
                    "title",
                    "Draft title",
                    "body",
                    "<!-- wp:paragraph --><p>draft body</p><!-- /wp:paragraph -->",
                    "excerpt",
                    ""
            ))
            .put("/admin/api/items/" + id + "/autosave")
            .then()
            .statusCode(204);
        RestAssured
            .given()
            .cookie("sp_session", author1)
            .get("/admin/preview/" + id)
            .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("no-store"))
            .header("X-Robots-Tag", equalTo("noindex"))
            .body(containsString("Draft title"))
            .body(containsString("draft body"));
        assertThat(Files.readString(page)).contains("Old title").doesNotContain("Draft title");
    }

    @Test
    void anonymousIs401AndOtherAuthorIs403() {
        var id = ItemsResourceTest.create(author1, "post").getLong("id");
        RestAssured.given().get("/admin/preview/" + id).then().statusCode(401);
        RestAssured.given().cookie("sp_session", author2).get("/admin/preview/" + id).then().statusCode(403);
    }

    @Test
    void previewIsNotSwallowedByQuinoa() {
        var id = ItemsResourceTest.create(author1, "post").getLong("id");
        RestAssured
            .given()
            .cookie("sp_session", author1)
            .get("/admin/preview/" + id)
            .then()
            .statusCode(200)
            .contentType(startsWith("text/html"))
            .body(not(containsString("<div id=\"root\"")));
        // a path no endpoint matches stays a 404, not the admin shell
        RestAssured
            .given()
            .cookie("sp_session", author1)
            .get("/admin/preview/no-such-thing")
            .then()
            .statusCode(404)
            .body(not(containsString("<div id=\"root\"")));
    }
}
