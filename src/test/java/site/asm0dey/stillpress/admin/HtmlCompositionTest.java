package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
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
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Lower roles combining fragments that are harmless alone (R27): each attack is refused at the save that would
 * complete it, and the refusal writes nothing and keeps the import open.
 */
@QuarkusTest
class HtmlCompositionTest {
    private static final String HALF = "<img src=x alt='";
    private static final String REST = "' onerror=alert(1)>";
    private static final String EDITORS =
            "onerror attribute on <img> in a reusable block owned by an editor; an editor must change it";
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
    String author;
    String editor;

    @BeforeEach
    void reset() {
        store.reset();
        author = sessions.create(users.create("author", "a@example.org", "Author", Role.AUTHOR).getId());
        editor = sessions.create(users.create("editor", "e@example.org", "Editor", Role.EDITOR).getId());
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void formatOnlyUpdateIsChecked() {
        var b = block(editor, HALF);
        var p = ItemsResourceTest.create(author, "post");
        var saved = ItemsResourceTest
            .update(author, p, Map.of("body", ref(b) + REST, "bodyFormat", "classic"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        reopenImport();
        ItemsResourceTest
            .update(author, saved, Map.of("bodyFormat", "blocks", "status", "publish"))
            .then()
            .statusCode(400)
            .body("error", equalTo("unsafe_html"))
            .body("violations", contains(EDITORS));
        var now = ItemsResourceTest.get(author, p.getLong("id"));
        assertThat(List.of(now.getString("bodyFormat"), now.getString("status"))).containsExactly("classic", "draft");
        assertImportOpen();
    }

    @Test
    void restoreFromTrashIsChecked() {
        var b = block(author, "x");
        var p = ItemsResourceTest.create(author, "post");
        ItemsResourceTest.update(author, p, Map.of("body", ref(b) + REST)).then().statusCode(200);
        as(author).delete("/admin/api/items/" + p.getLong("id")).then().statusCode(204);
        // an editor's edit is not checked
        as(editor).body(Map.of("content", HALF)).put("/admin/api/blocks/" + b).then().statusCode(200);
        reopenImport();
        as(author)
            .post("/admin/api/items/" + p.getLong("id") + "/restore")
            .then()
            .statusCode(400)
            .body("violations", contains("onerror attribute on <img>"));
        assertThat(ItemsResourceTest.get(author, p.getLong("id")).getString("status")).isEqualTo("trash");
        assertImportOpen();
    }

    @Test
    void blockEditChecksTrashedItemsToo() {
        var b = block(author, "x");
        var p = ItemsResourceTest.create(author, "post");
        ItemsResourceTest.update(author, p, Map.of("title", "Old", "body", ref(b))).then().statusCode(200);
        as(author).delete("/admin/api/items/" + p.getLong("id")).then().statusCode(204);
        // imported content is never checked
        db.update(ITEM).set(ITEM.BODY, ref(b) + "<p onclick=x>a</p>").where(ITEM.ID.eq(p.getLong("id"))).execute();
        reopenImport();
        as(author)
            .body(Map.of("content", "y"))
            .put("/admin/api/blocks/" + b)
            .then()
            .statusCode(400)
            .body("violations", contains("in \"Old\": onclick attribute on <p>"));
        as(author).get("/admin/api/blocks/" + b).then().body("content", equalTo("x"));
        assertImportOpen();
    }

    @Test
    void blockCreatedForAFutureRefIsChecked() {
        var next = block(author, "x") + 1;
        var p = ItemsResourceTest.create(author, "post");
        ItemsResourceTest
            .update(author, p, Map.of("title", "Waiting", "body", "<!-- wp:block {\"ref\":" + next + "} /-->" + REST))
            .then()
            .statusCode(200);
        reopenImport();
        createBlock(author, HALF).then().statusCode(400).body("violations", contains("unclosed markup at the end"));
        // a harmless block for an item that already holds script: checked after the insert, rolled back
        db
            .update(ITEM)
            .set(ITEM.BODY, "<!-- wp:block {\"ref\":" + next + "} /--><p onclick=x>a</p>")
            .where(ITEM.ID.eq(p.getLong("id")))
            .execute();
        createBlock(author, "y")
            .then()
            .statusCode(400)
            .body("violations", contains("in \"Waiting\": onclick attribute on <p>"));
        assertThat(db.fetchCount(REUSABLE_BLOCK)).isEqualTo(1);
        assertImportOpen();
    }

    @Test
    void unclosedExcerptIsRefused() {
        var a = ItemsResourceTest.create(author, "post");
        reopenImport();
        ItemsResourceTest
            .update(author, a, Map.of("excerpt", HALF))
            .then()
            .statusCode(400)
            .body("field", equalTo("excerpt"))
            .body("violations", contains("unclosed markup at the end"));
        assertThat(ItemsResourceTest.get(author, a.getLong("id")).getString("excerpt")).isEqualTo("");
        assertImportOpen();
    }

    private void reopenImport() {
        settings.delete("import_state");
    }

    private void assertImportOpen() {
        assertThat(settings.get("import_state")).isNotPresent();
    }

    private static String ref(long block) {
        return "<!-- wp:block {\"ref\":" + block + "} /-->";
    }

    private static long block(String cookie, String content) {
        return createBlock(cookie, content).then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private static io.restassured.response.Response createBlock(String cookie, String content) {
        return as(cookie).body(Map.of("title", "T", "content", content)).post("/admin/api/blocks");
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }

    @Test
    void sampleImportedPostsSaveUnchanged() {
        var doc = site.asm0dey.stillpress.wxr.WxrDocument.read(getClass()
            .getClassLoader()
            .getResourceAsStream("wxr/sample-blog.xml")
        );
        var authorId = users.findByLogin("author").orElseThrow().getId();
        var posts = doc
            .items()
            .stream()
            .filter(i -> "post".equals(i.postType()))
            .toList();
        assertThat(posts).isNotEmpty();
        for (var i : posts) {
            var p = ItemsResourceTest.create(author, "post");
            db
                .update(ITEM)
                .set(ITEM.BODY, i.content())
                .set(ITEM.EXCERPT, i.excerpt())
                .set(ITEM.BODY_FORMAT, i.content().contains("<!-- wp:") ? "blocks" : "classic")
                .set(ITEM.AUTHOR_ID, authorId)
                .where(ITEM.ID.eq(p.getLong("id")))
                .execute();
            var stored = ItemsResourceTest.get(author, p.getLong("id"));
            ItemsResourceTest.update(author, stored, Map.of("title", i.title())).then().statusCode(200);
        }
    }
}
