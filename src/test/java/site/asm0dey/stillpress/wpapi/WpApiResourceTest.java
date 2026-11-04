package site.asm0dey.stillpress.wpapi;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
class WpApiResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    DSLContext db;
    String editor;
    String author1;
    String author2;
    String contributor;

    @BeforeEach
    void reset() {
        store.reset();
        editor = cookie("editor", Role.EDITOR);
        author1 = cookie("author1", Role.AUTHOR);
        author2 = cookie("author2", Role.AUTHOR);
        contributor = cookie("contributor", Role.CONTRIBUTOR);
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void anonymousIs401() {
        given().get("/wp-json/wp/v2/types").then().statusCode(401);
    }

    @Test
    void methodOverridePutUpdatesBlock() {
        var id = createBlock(editor);
        var content = "<!-- wp:paragraph --><p>Updated</p><!-- /wp:paragraph -->";
        as(editor)
            .header("X-HTTP-Method-Override", "PUT")
            .body(Map.of("content", content))
            .post("/wp-json/wp/v2/blocks/" + id)
            .then()
            .statusCode(200)
            .body("id", equalTo((int) id))
            .body("type", equalTo("wp_block"))
            .body("content.raw", equalTo(content));
        assertThat(blocks.find(id).orElseThrow().getContent()).isEqualTo(content);
        // POST to a block is itself an update, so DELETE shows the override is applied: deleting is not offered
        as(editor)
            .header("X-HTTP-Method-Override", "DELETE")
            .post("/wp-json/wp/v2/blocks/" + id)
            .then()
            .statusCode(404)
            .body("code", equalTo("rest_no_route"));
        assertThat(blocks.find(id).orElseThrow().getContent()).isEqualTo(content);
    }

    @Test
    void overrideWithoutCsrfIs400() {
        var id = createBlock(editor);
        given()
            .contentType(JSON)
            .cookie("sp_session", editor)
            .header("X-HTTP-Method-Override", "PUT")
            .body(Map.of("content", "changed"))
            .post("/wp-json/wp/v2/blocks/" + id)
            .then()
            .statusCode(400);
        assertThat(blocks.find(id).orElseThrow().getContent()).isEqualTo("first");
    }

    @Test
    void authorSavingOthersBlockThroughWpIs403() {
        var id = createBlock(author1);
        as(author2).body(Map.of("content", "taken")).put("/wp-json/wp/v2/blocks/" + id).then().statusCode(403);
        assertThat(blocks.find(id).orElseThrow().getContent()).isEqualTo("first");
    }

    @Test
    void optionsAllowDependsOnRole() {
        var id = createBlock(author1);
        var block = "/wp-json/wp/v2/blocks/" + id;
        as(editor).options(block).then().statusCode(200).header("Allow", containsString("PUT"));
        as(author1).options(block).then().statusCode(200).header("Allow", containsString("PUT"));
        as(author2).options(block).then().statusCode(200).header("Allow", equalTo("GET"));
        as(author1).options("/wp-json/wp/v2/blocks").then().statusCode(200).header("Allow", containsString("POST"));
        as(contributor).options("/wp-json/wp/v2/blocks").then().statusCode(200).header("Allow", equalTo("GET"));
    }

    @Test
    void unknownRouteIs404AndLogged() {
        var lines = new CopyOnWriteArrayList<String>();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                lines.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        var logger = Logger.getLogger(WpApiResource.class.getName());
        logger.addHandler(handler);
        try {
            as(editor).get("/wp-json/wp/v2/settings?context=edit").then().statusCode(404).body(
                    "code",
                    equalTo("rest_no_route")
            );
            as(editor).delete("/wp-json/wp/v2/whatever").then().statusCode(404).body("code", equalTo("rest_no_route"));
            as(editor).patch("/wp-json/x").then().statusCode(404).body("code", equalTo("rest_no_route"));
            // a defined path with a method it does not have
            as(editor).put("/wp-json/wp/v2/types").then().statusCode(404).body("code", equalTo("rest_no_route"));
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(lines)
            .containsExactly(
                    "WARN Unknown /wp-json call: GET /wp-json/wp/v2/settings?context=edit",
                    "WARN Unknown /wp-json call: DELETE /wp-json/wp/v2/whatever",
                    "WARN Unknown /wp-json call: PATCH /wp-json/x",
                    "WARN Unknown /wp-json call: PUT /wp-json/wp/v2/types"
            );
    }

    @Test
    void catchAllDoesNotSwallowDefinedRoutes() {
        var id = createBlock(editor);
        as(editor).get("/wp-json/wp/v2/blocks/" + id).then().statusCode(200).body("title.raw", equalTo("Block"));
        as(editor).get("/wp-json/wp/v2/blocks").then().statusCode(200).body("id", hasItems((int) id));
        as(editor).get("/wp-json/wp/v2/users/me").then().statusCode(200).body("slug", equalTo("editor"));
        as(editor)
            .get("/wp-json/wp/v2/themes?status=active")
            .then()
            .statusCode(200)
            .body("[0].stylesheet", equalTo("plain"))
            .body("[0].theme_supports.align-wide", equalTo(true));
        as(editor).get("/wp-json/wp/v2/media/999999").then().statusCode(404).body(
                "code",
                equalTo("rest_post_invalid_id")
        );
    }

    @Test
    void typesListsPostPageAndBlock() {
        as(contributor)
            .get("/wp-json/wp/v2/types")
            .then()
            .statusCode(200)
            .body("post.rest_base", equalTo("posts"))
            .body("page.rest_base", equalTo("pages"))
            .body("wp_block.rest_base", equalTo("blocks"))
            .body("attachment.rest_base", equalTo("media"));
        as(contributor).get("/wp-json/wp/v2/types/wp_block").then().statusCode(200).body("slug", equalTo("wp_block"));
    }

    private long createBlock(String cookie) {
        return as(cookie)
            .body(Map.of("title", "Block", "content", "first", "status", "publish"))
            .post("/wp-json/wp/v2/blocks")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    }

    private String cookie(String login, Role role) {
        var id = users.create(login, login + "@example.org", login, role).getId();
        users.setPassword(id, Passwords.hash("correct horse battery"));
        return sessions.create(id);
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }
}
