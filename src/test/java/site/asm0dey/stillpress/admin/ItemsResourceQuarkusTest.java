package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.REVISION;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ItemsResourceQuarkusTest {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
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
    @Inject
    ItemRepository items;
    String admin;
    String editor;
    String author1;
    String author2;
    String contributor;
    long author2Id;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("timezone", "Europe/Moscow");
        admin = cookie("admin", Role.ADMINISTRATOR);
        editor = cookie("editor", Role.EDITOR);
        author1 = cookie("author1", Role.AUTHOR);
        author2 = cookie("author2", Role.AUTHOR);
        author2Id = users.findByLogin("author2").orElseThrow().id();
        contributor = cookie("contributor", Role.CONTRIBUTOR);
    }

    @AfterEach
    void settle() {
        // the next test's reset must not race a background publish
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void createEditPublishWritesPageAtStructurePermalink() {
        var item = create(author1, "post");
        var saved = update(author1, item, Map.of("title", "Привет мир", "status", "publish"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        var day = LocalDate.now(MOSCOW).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(saved.getString("permalink")).isEqualTo("/" + day + "/привет-мир/");
        assertThat(saved.getString("status")).isEqualTo("publish");
        var page = page(saved.getString("permalink"));
        TestStore.await(() -> Files.exists(page), 30);
        assertThat(page).exists();
    }

    @Test
    void editorMaySaveScript() {
        var item = create(author1, "post");
        update(editor, item, Map.of("body", "<p onclick=\"go()\">a</p>"))
            .then()
            .statusCode(200)
            .body("body", equalTo("<p onclick=\"go()\">a</p>"));
    }

    @Test
    void futureDateMakesItScheduled() {
        var item = create(author1, "post");
        var tomorrow = LocalDateTime.now(MOSCOW).plusDays(1).withSecond(7).withNano(0);
        update(author1, item, Map.of("title", "Later", "status", "publish", "localDate", tomorrow.toString()))
            .then()
            .statusCode(200)
            .body("status", equalTo("future"))
            .body("permalink", nullValue())
            .body("date", equalTo(tomorrow.toString()));
        assertThat(db
            .select(SCHEDULED_TASKS.TASK_INSTANCE)
            .from(SCHEDULED_TASKS)
            .where(SCHEDULED_TASKS.TASK_NAME.eq(Jobs.GO_LIVE))
            .fetch(SCHEDULED_TASKS.TASK_INSTANCE)
        )
            .containsExactly(item.getLong("id") + "@" + tomorrow.atZone(MOSCOW).toEpochSecond());
    }

    @Test
    void clearingParentMovesPageToTopLevel() {
        var parent = update(editor, create(editor, "page"), Map.of("title", "Guide", "status", "publish"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        var child = update(
                editor,
                create(editor, "page"),
                Map.of("title", "Tips", "status", "publish", "parentId", parent.getLong("id"))
        )
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        assertThat(child.getString("permalink")).isEqualTo("/guide/tips/");
        var cleared = update(editor, child, Map.of("parentId", 0)).then().statusCode(200).extract().jsonPath();
        assertThat(cleared.getString("parentId")).isNull();
        assertThat(cleared.getString("permalink")).isEqualTo("/tips/");
        assertThat(redirects.find("/guide/tips/").orElseThrow().to()).isEqualTo("/tips/");
    }

    @Test
    void slugChangeOnPublishedPostRedirectsOldPath() {
        var item = create(author1, "post");
        var published = update(author1, item, Map.of("title", "Old", "status", "publish"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        var oldPath = published.getString("permalink");
        var renamed = update(author1, published, Map.of("slug", "new-slug"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        var newPath = renamed.getString("permalink");
        assertThat(newPath).isEqualTo(oldPath.replace("/old/", "/new-slug/"));
        assertThat(redirects.find(oldPath).orElseThrow().to()).isEqualTo(newPath);
        // the shortlink follows the move instead of chaining through the old path
        var sourceId =
                db.select(ITEM.SOURCE_ID).from(ITEM).where(ITEM.ID.eq(item.getLong("id"))).fetchOne(ITEM.SOURCE_ID);
        assertThat(redirects.find("?p=" + sourceId).orElseThrow().to()).isEqualTo(newPath);
    }

    @Test
    void duplicateTitlesGetUniqueSlugs() {
        var first = update(author1, create(author1, "post"), Map.of("title", "Same", "status", "publish")).jsonPath();
        var second = update(author1, create(author1, "post"), Map.of("title", "Same", "status", "publish")).jsonPath();
        assertThat(List.of(first.getString("slug"), second.getString("slug"))).containsExactly("same", "same-2");
    }

    @Test
    void trashUnpublishesAndRestoreGivesDraft() {
        var published =
                update(author1, create(author1, "post"), Map.of("title", "Gone soon", "status", "publish")).jsonPath();
        var page = page(published.getString("permalink"));
        TestStore.await(() -> Files.exists(page), 30);
        assertThat(page).exists();
        var id = published.getLong("id");
        as(author2).delete("/admin/api/items/" + id).then().statusCode(403);
        as(author1).delete("/admin/api/items/" + id).then().statusCode(204);
        assertThat(get(author1, id).getString("status")).isEqualTo("trash");
        TestStore.await(() -> !Files.exists(page), 30);
        assertThat(page).doesNotExist();
        as(author1).post("/admin/api/items/" + id + "/restore").then().statusCode(200).body("status", equalTo("draft"));
    }

    @Test
    // test input
    @SuppressWarnings("java:S8786")
    void newItemIdSkipsImportedIdsAndGetsShortlink() throws Exception {
        store.importSample();
        var max = db.select(DSL.max(ITEM.SOURCE_ID)).from(ITEM).fetchOne(DSL.max(ITEM.SOURCE_ID));
        var published = update(admin, create(admin, "post"), Map.of("title", "Fresh", "status", "publish"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        var home = settings.get("home_url").orElseThrow().replaceAll("/+$", "");
        assertThat(db.select(ITEM.GUID).from(ITEM).where(ITEM.ID.eq(published.getLong("id"))).fetchOne(ITEM.GUID))
            .isEqualTo(home + "/?p=" + (max + 1));
        assertThat(redirects.find("?p=" + (max + 1)).orElseThrow().to()).isEqualTo(published.getString("permalink"));
        assertThat(redirects.find("?p=5994").orElseThrow().to()).isEqualTo("/2023/01/14/месяц-ноябрь-год-2022/");
    }

    @Test
    void listSerializesRowsTotalAndStatusCounts() {
        update(author1, create(author1, "post"), Map.of("title", "Out", "status", "publish")).then().statusCode(200);
        update(author1, create(author1, "post"), Map.of("title", "Draft one")).then().statusCode(200);
        var mine = as(author1)
            .get("/admin/api/items?type=post&status=any")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        assertThat(mine.getInt("total")).isEqualTo(2);
        assertThat(mine.getList("items.title", String.class)).containsExactly("Draft one", "Out");
        assertThat(mine.getMap("counts", String.class, Integer.class)).isEqualTo(Map.of("draft", 1, "publish", 1));
    }

    @Test
    void autosaveRoundTripShowsUpOnTheItemUntilTheNextSave() {
        var item = create(author1, "post");
        var id = item.getLong("id");
        as(author1)
            .body(Map.of("title", "Draft title", "body", "b", "excerpt", "e"))
            .put("/admin/api/items/" + id + "/autosave")
            .then()
            .statusCode(204);
        as(author1)
            .get("/admin/api/items/" + id + "/autosave")
            .then()
            .statusCode(200)
            .body("title", equalTo("Draft title"))
            .body("body", equalTo("b"))
            .body("excerpt", equalTo("e"));
        assertThat(get(author1, id).getString("autosave.createdAt")).isNotNull();
        update(author1, item, Map.of("title", "Saved")).then().statusCode(200).body("autosave", nullValue());
        as(author1).get("/admin/api/items/" + id + "/autosave").then().statusCode(404);
    }

    @Test
    void anonymousIs401OnEveryEndpoint() {
        for (var call :
                List.of(
                        List.of("GET", "/admin/api/items"),
                        List.of("POST", "/admin/api/items"),
                        List.of("GET", "/admin/api/items/1"),
                        List.of("PUT", "/admin/api/items/1"),
                        List.of("DELETE", "/admin/api/items/1"),
                        List.of("POST", "/admin/api/items/1/restore"),
                        List.of("PUT", "/admin/api/items/1/autosave"),
                        List.of("GET", "/admin/api/items/1/autosave"),
                        List.of("GET", "/admin/api/items/1/revisions"),
                        List.of("GET", "/admin/api/revisions/1"),
                        List.of("POST", "/admin/api/items/1/revisions/1/restore")
        )) {
            AuthMatrix.anonymousIs401(call.get(0), call.get(1));
        }
    }

    @Test
    void anotherAuthorIs403OnEveryEndpointOfSomeoneElsesPostAndAuthorsMayNotCreatePages() {
        var item = create(author1, "post");
        var id = item.getLong("id");
        update(author1, item, Map.of("title", "Mine")).then().statusCode(200);
        var revision = as(author1).get("/admin/api/items/" + id + "/revisions").jsonPath().getLong("[0].id");
        var path = "/admin/api/items/" + id;
        AuthMatrix.forbiddenFor(author2, "GET", path);
        AuthMatrix.forbiddenFor(
                author2,
                "PUT",
                path,
                "{\"title\":\"Taken\",\"expectedModifiedAt\":\"" + get(author1, id).getString("modifiedAt") + "\"}"
        );
        AuthMatrix.forbiddenFor(author2, "DELETE", path);
        AuthMatrix.forbiddenFor(author2, "POST", path + "/restore");
        AuthMatrix.forbiddenFor(author2, "PUT", path + "/autosave");
        AuthMatrix.forbiddenFor(author2, "GET", path + "/autosave");
        AuthMatrix.forbiddenFor(author2, "GET", path + "/revisions");
        AuthMatrix.forbiddenFor(author2, "GET", "/admin/api/revisions/" + revision);
        AuthMatrix.forbiddenFor(author2, "POST", path + "/revisions/" + revision + "/restore");
        AuthMatrix.forbiddenFor(author1, "POST", "/admin/api/items", "{\"type\":\"page\"}");
        assertThat(get(author1, id).getString("title")).isEqualTo("Mine");
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        AuthMatrix.missingCsrfIs400(author1, "POST", "/admin/api/items");
        AuthMatrix.missingCsrfIs400(author1, "PUT", "/admin/api/items/1");
        AuthMatrix.missingCsrfIs400(author1, "DELETE", "/admin/api/items/1");
        AuthMatrix.missingCsrfIs400(author1, "PUT", "/admin/api/items/1/autosave");
    }

    @Test
    void restoringRevisionOfPublishedItemRepublishes() throws Exception {
        var first = update(author1, create(author1, "post"), Map.of("title", "First", "status", "publish")).jsonPath();
        update(author1, first, Map.of("title", "Second")).then().statusCode(200);
        var page = page(first.getString("permalink"));
        TestStore.await(() -> contains(page, "Second"), 30);
        var id = first.getLong("id");
        var revisions =
                as(author1)
            .get("/admin/api/items/" + id + "/revisions")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
        assertThat(revisions.getList("id")).hasSize(2);
        var oldest = revisions.getLong("[1].id");
        as(author1).get("/admin/api/revisions/" + oldest).then().statusCode(200).body("title", equalTo("First"));
        as(author2).get("/admin/api/revisions/" + oldest).then().statusCode(403);
        as(author1)
            .post("/admin/api/items/" + id + "/revisions/" + oldest + "/restore")
            .then()
            .statusCode(200)
            .body("title", equalTo("First"))
            .body("status", equalTo("publish"))
            .body("permalink", equalTo(first.getString("permalink")));
        TestStore.await(() -> contains(page, "First"), 30);
        assertThat(Files.readString(page)).contains("First");
    }

    @Test
    void newTermIsCreatedOnceAndListed() {
        as(contributor).body(Map.of("taxonomy", "post_tag", "name", "Java")).post("/admin/api/terms").then().statusCode(
                403
        );
        var id = as(author1)
            .body(Map.of("taxonomy", "post_tag", "name", "Java"))
            .post("/admin/api/terms")
            .then()
            .statusCode(201)
            .body("slug", equalTo("java"))
            .extract()
            .jsonPath()
            .getLong("id");
        as(author1)
            .body(Map.of("taxonomy", "post_tag", "name", "JAVA"))
            .post("/admin/api/terms")
            .then()
            .statusCode(201)
            .body("slug", equalTo("java-2"));
        var item = create(author1, "post");
        update(author1, item, Map.of("title", "Tagged", "tags", List.of(id)))
            .then()
            .statusCode(200)
            .body("tags", equalTo(List.of((int) id)));
        assertThat(as(author1).get("/admin/api/terms?taxonomy=post_tag").jsonPath().getList("name"))
            .containsExactlyInAnyOrder("Java", "JAVA");
    }

    @Test
    void parallelAutosavesLeaveOneAutosave() throws Exception {
        var id = create(author1, "post").getLong("id");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var saves = IntStream
                .range(0, 8)
                .mapToObj(i -> pool.submit(() -> as(author1)
                    .body(Map.of("title", "t" + i, "body", "b", "excerpt", ""))
                    .put("/admin/api/items/" + id + "/autosave")
                    .statusCode()))
                .toList();
            for (var f : saves) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(204);
            }
        }
        as(author1).get("/admin/api/items/" + id).then().statusCode(200);
        assertThat(db.fetchCount(REVISION, REVISION.OBJECT_ID.eq(id), REVISION.AUTOSAVE.isTrue())).isEqualTo(1);
    }

    @Test
    void concurrentSavesOfOneItemGiveOne200AndOne409() throws Exception {
        var item = create(author1, "post");
        var id = item.getLong("id");
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            // the row stays locked until both saves are waiting on it
            var holder = pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                items.lock(id).orElseThrow();
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            held.await(10, TimeUnit.SECONDS);
            var saves = List
                .of("one", "two")
                .stream()
                .map(t -> pool.submit(() -> update(author1, item, Map.of("title", t)).statusCode()))
                .toList();
            Thread.sleep(1000);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            var codes = new ArrayList<Integer>();
            for (var f : saves) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void categoryChangeMovesCategoryPermalink() {
        settings.put("permalink_structure", "/%category%/%postname%/");
        var travel = term(editor, "category", "Travel");
        var food = term(editor, "category", "Food");
        var published = update(
                author1,
                create(author1, "post"),
                Map.of("title", "Trip", "status", "publish", "categories", List.of(travel))
        )
            .then()
            .statusCode(200)
            .body("permalink", equalTo("/travel/trip/"))
            .extract()
            .jsonPath();
        update(author1, published, Map.of("categories", List.of(food)))
            .then()
            .statusCode(200)
            .body("permalink", equalTo("/food/trip/"));
        assertThat(redirects.find("/travel/trip/").orElseThrow().to()).isEqualTo("/food/trip/");
    }

    @Test
    void authorChangeMovesAuthorPermalink() {
        settings.put("permalink_structure", "/%author%/%postname%/");
        var a1 = users.findByLogin("author1").orElseThrow().slug();
        var a2 = users.findByLogin("author2").orElseThrow().slug();
        var published = update(author1, create(author1, "post"), Map.of("title", "Byline", "status", "publish"))
            .then()
            .statusCode(200)
            .body("permalink", equalTo("/" + a1 + "/byline/"))
            .extract()
            .jsonPath();
        update(editor, published, Map.of("authorId", author2Id))
            .then()
            .statusCode(200)
            .body("permalink", equalTo("/" + a2 + "/byline/"));
        assertThat(redirects.find("/" + a1 + "/byline/").orElseThrow().to()).isEqualTo("/" + a2 + "/byline/");
    }

    private static long term(String cookie, String taxonomy, String name) {
        return as(cookie)
            .body(Map.of("taxonomy", taxonomy, "name", name))
            .post("/admin/api/terms")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    }

    private Path page(String permalink) {
        return dataDir.current().resolve(permalink.substring(1)).resolve("index.html");
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

    static JsonPath create(String cookie, String type) {
        return as(cookie)
            .body(Map.of("type", type))
            .post("/admin/api/items")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    }

    static JsonPath get(String cookie, long id) {
        return as(cookie).get("/admin/api/items/" + id).then().statusCode(200).extract().jsonPath();
    }

    /**
     * PUT with the item's own {@code modifiedAt} as {@code expectedModifiedAt}.
     */
    static Response update(String cookie, JsonPath item, Map<String, Object> fields) {
        var body = new HashMap<String, Object>(fields);
        body.put("expectedModifiedAt", item.getString("modifiedAt"));
        return as(cookie).body(body).put("/admin/api/items/" + item.getLong("id"));
    }
}
