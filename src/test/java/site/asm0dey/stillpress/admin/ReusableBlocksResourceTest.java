package site.asm0dey.stillpress.admin;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
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

@QuarkusTest
class ReusableBlocksResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    DSLContext db;
    @Inject
    site.asm0dey.stillpress.importer.WordPressImporter importer;
    @Inject
    site.asm0dey.stillpress.store.SettingRepository settings;
    String author1;
    String author2;
    long author1Id;

    @BeforeEach
    void reset() {
        store.reset();
        author1Id = users.create("author1", "a1@example.org", "Author One", Role.AUTHOR).getId();
        author1 = sessions.create(author1Id);
        author2 = sessions.create(users.create("author2", "a2@example.org", "Author Two", Role.AUTHOR).getId());
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void importedBlockCanBeSavedWithItsModifiedAt() throws Exception {
        importer.importWxr(site.asm0dey.stillpress.TestStore.sample());
        var admin = sessions.create(users.create("root", "root@example.org", "Root", Role.ADMINISTRATOR).getId());
        var id = db
            .select(site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK.SOURCE_ID)
            .from(site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK)
            .fetchOne(site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK.SOURCE_ID);
        var modifiedAt = as(admin)
            .get("/admin/api/blocks/" + id)
            .then()
            .statusCode(200)
            .extract()
            .path("modifiedAt");
        assertThat(modifiedAt).isNotNull();
        as(admin)
            .body(Map.of(
                    "title",
                    "T",
                    "content",
                    "<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->",
                    "expectedModifiedAt",
                    modifiedAt
            ))
            .put("/admin/api/blocks/" + id)
            .then()
            .statusCode(200);
    }

    @Test
    void listShowsOwnerUsageAndCapability() {
        var b = create(author1, "one");
        var item = ItemsResourceTest.create(author1, "post").getLong("id");
        db
            .update(ITEM)
            .set(ITEM.BODY, "<!-- wp:block {\"ref\":" + b.getLong("id") + "} /-->")
            .where(ITEM.ID.eq(item))
            .execute();
        as(author2)
            .get("/admin/api/blocks")
            .then()
            .statusCode(200)
            .body("[0].id", equalTo(b.getInt("id")))
            .body("[0].author", equalTo("Author One"))
            .body("[0].usedBy", equalTo(1))
            .body("[0].canEdit", equalTo(false))
            .body("[0].content", nullValue());
    }

    @Test
    void listWithContentCarriesEachBlocksContent() {
        var a = create(author1, "one");
        var b = create(author2, "two");
        as(author2)
            .get("/admin/api/blocks?content=true")
            .then()
            .statusCode(200)
            .body("id", equalTo(List.of(a.getInt("id"), b.getInt("id"))))
            .body("content", equalTo(List.of("one", "two")))
            .body("canEdit", equalTo(List.of(false, true)));
    }

    @Test
    void saveReportsThePublishedItemsItRepublishesThroughOtherBlocksToo() {
        var inner = create(author1, "inner");
        var outer = create(author1, "<!-- wp:block {\"ref\":" + inner.getLong("id") + "} /-->");
        var draft = ItemsResourceTest.create(author1, "post");
        ItemsResourceTest
            .update(author1, draft, Map.of("body", "<!-- wp:block {\"ref\":" + inner.getLong("id") + "} /-->"))
            .then()
            .statusCode(200);
        var published = ItemsResourceTest.create(author1, "post");
        ItemsResourceTest
            .update(
                    author1,
                    published,
                    Map.of(
                            "title",
                            "Shows outer",
                            "status",
                            "publish",
                            "body",
                            "<!-- wp:block {\"ref\":" + outer.getLong("id") + "} /-->"
                    )
            )
            .then()
            .statusCode(200);
        put(author1, inner, "changed").then().statusCode(200).body("republished", equalTo(1)).body("usedBy", equalTo(1));
    }

    @Test
    void staleExpectedModifiedAtIs409() {
        var b = create(author1, "one");
        put(author1, b, "two").then().statusCode(200);
        put(author1, b, "three").then().statusCode(409).body("error", equalTo("conflict"));
        as(author1).get("/admin/api/blocks/" + b.getLong("id")).then().body("content", equalTo("two"));
    }

    @Test
    void missingExpectedModifiedAtIs400AndWritesNothing() {
        var b = create(author1, "one");
        as(author1)
            .body(Map.of("content", "four"))
            .put("/admin/api/blocks/" + b.getLong("id"))
            .then()
            .statusCode(400)
            .body("error", equalTo("invalid"))
            .body("field", equalTo("expectedModifiedAt"));
        as(author1)
            .get("/admin/api/blocks/" + b.getLong("id"))
            .then()
            .body("content", equalTo("one"))
            .body("modifiedAt", equalTo(b.getString("modifiedAt")));
        as(author1).get("/admin/api/blocks/" + b.getLong("id") + "/revisions").then().body("size()", equalTo(1));
    }

    @Test
    void autosaveRoundTrip() {
        var b = create(author1, "one");
        var path = "/admin/api/blocks/" + b.getLong("id");
        as(author1).body(Map.of("title", "T", "content", "draft")).put(path + "/autosave").then().statusCode(204);
        as(author1)
            .get(path)
            .then()
            .statusCode(200)
            .body("content", equalTo("one"))
            .body("autosave.createdAt", notNullValue());
        as(author1).get(path + "/autosave").then().statusCode(200).body("content", equalTo("draft"));
        as(author2).get(path + "/autosave").then().statusCode(404);
        as(author2).body(Map.of("title", "T", "content", "x")).put(path + "/autosave").then().statusCode(403);
        put(author1, b, "saved").then().statusCode(200).body("autosave", nullValue());
    }

    @Test
    void anonymousOEmbedIs401() {
        given().get("/admin/api/oembed?url=https://youtu.be/x").then().statusCode(401);
    }

    @Test
    void revisionRestoreSavesOldContent() {
        var b = create(author1, "one");
        var path = "/admin/api/blocks/" + b.getLong("id");
        put(author1, b, "two").then().statusCode(200);
        var revs = as(author1).get(path + "/revisions").then().statusCode(200).extract().jsonPath();
        var first = revs.getList("id", Long.class).getLast();
        as(author1).get(path + "/revisions/" + first).then().statusCode(200).body("content", equalTo("one"));
        as(author1)
            .post(path + "/revisions/" + first + "/restore")
            .then()
            .statusCode(200)
            .body("content", equalTo("one"))
            .body("republished", equalTo(0));
        as(author2).post(path + "/revisions/" + first + "/restore").then().statusCode(403);
        assertThat(as(author1).get(path + "/revisions").then().extract().jsonPath().getList("id")).hasSize(3);
    }

    @Test
    void authorEditingOthersBlockIs403AndChangesNothing() {
        var b = create(author1, "one");
        put(author2, b, "taken").then().statusCode(403).body("error", equalTo("forbidden"));
        as(author1).get("/admin/api/blocks/" + b.getLong("id")).then().body("content", equalTo("one"));
    }

    @Test
    void authorScriptInOwnBlockIs400EditorIs200() {
        as(author1)
            .body(Map.of("title", "T", "content", "<script>x</script>"))
            .post("/admin/api/blocks")
            .then()
            .statusCode(400)
            .body("error", equalTo("unsafe_html"))
            .body("violations", org.hamcrest.Matchers.contains("<script> element"));
        var b = create(author1, "one");
        put(author1, b, "<script>x</script>").then().statusCode(400).body("error", equalTo("unsafe_html"));
        as(author1)
            .body(Map.of("title", "T", "content", "<script>x</script>"))
            .put("/admin/api/blocks/" + b.getLong("id") + "/autosave")
            .then()
            .statusCode(400);
        as(author1).get("/admin/api/blocks/" + b.getLong("id")).then().body("content", equalTo("one"));
        var editor = sessions.create(users.create("editor", "e@example.org", "Ed", Role.EDITOR).getId());
        put(editor, b, "<script>x</script>").then().statusCode(200).body("content", equalTo("<script>x</script>"));
    }

    @Test
    void blockAndPostThatOnlyTogetherRunScriptAreRefusedInEitherOrder() {
        // a tag split across a reusable block and the post that shows it; each half alone is plain text
        var half = "<img src=x alt='";
        var rest = "' onerror=alert(1)>";
        settings.delete("import_state");
        // an author's half is unclosed markup, refused on its own (R27)
        as(author1)
            .body(Map.of("title", "T", "content", half))
            .post("/admin/api/blocks")
            .then()
            .statusCode(400)
            .body("violations", org.hamcrest.Matchers.contains("unclosed markup at the end"));
        // an editor's half is not checked; the post that completes the tag is
        var editor = sessions.create(users.create("editor", "e@example.org", "Ed", Role.EDITOR).getId());
        var b = create(editor, half);
        var post = ItemsResourceTest.create(author1, "post");
        var ref = "<!-- wp:block {\"ref\":" + b.getLong("id") + "} /-->";
        settings.delete("import_state");
        ItemsResourceTest
            .update(author1, post, Map.of("body", ref + rest))
            .then()
            .statusCode(400)
            .body("error", equalTo("unsafe_html"))
            .body(
                    "violations",
                    org.hamcrest.Matchers.contains(
                            "onerror attribute on <img> in a reusable block owned by an editor; an editor must change it"
                    )
            );
        // the review's payload 5: the block reference inside an attribute
        ItemsResourceTest
            .update(author1, post, Map.of("body", "<img src=x alt='" + ref + "'>"))
            .then()
            .statusCode(400)
            .body("violations", org.hamcrest.Matchers.contains("block comment inside HTML"));
        assertThat(ItemsResourceTest.get(author1, post.getLong("id")).getString("body")).isEqualTo("");
        assertThat(settings.get("import_state")).isNotPresent();
        // the other order: the post shows a harmless block, then the block is edited
        var c = create(author1, "x");
        var other = ItemsResourceTest.create(author1, "post");
        ItemsResourceTest
            .update(
                    author1,
                    other,
                    Map.of("title", "Split", "body", "<!-- wp:block {\"ref\":" + c.getLong("id") + "} /-->" + rest)
            )
            .then()
            .statusCode(200);
        settings.delete("import_state");
        put(author1, c, half)
            .then()
            .statusCode(400)
            .body("error", equalTo("unsafe_html"))
            .body("violations", org.hamcrest.Matchers.contains("unclosed markup at the end"));
        as(author1).get("/admin/api/blocks/" + c.getLong("id")).then().body("content", equalTo("x"));
        assertThat(settings.get("import_state")).isNotPresent();
    }

    private JsonPath create(String cookie, String content) {
        return as(cookie)
            .body(Map.of("title", "T", "content", content))
            .post("/admin/api/blocks")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    }

    private static io.restassured.response.Response put(String cookie, JsonPath block, String content) {
        var body = new HashMap<String, Object>(Map.of("title", "T", "content", content));
        body.put("expectedModifiedAt", block.getString("modifiedAt"));
        return as(cookie).body(body).put("/admin/api/blocks/" + block.getLong("id"));
    }

    private static RequestSpecification as(String cookie) {
        return csrf().contentType(JSON).cookie("sp_session", cookie);
    }
}
