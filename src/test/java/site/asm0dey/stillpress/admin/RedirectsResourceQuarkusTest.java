package site.asm0dey.stillpress.admin;

import static io.restassured.RestAssured.given;
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
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class RedirectsResourceQuarkusTest {
    private static final String POST = "/2023/01/14/месяц-ноябрь-год-2022/";
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
    DSLContext db;
    String admin;
    String editor;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("timezone", "UTC");
        settings.put("title", "Blog");
        admin = cookie("admin", Role.ADMINISTRATOR);
        editor = cookie("editor", Role.EDITOR);
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void addedRedirectIsServed() {
        add(admin, "/old-page/", POST, 301).then().statusCode(201);
        given()
            .redirects()
            .follow(false)
            .get("/old-page/")
            .then()
            .statusCode(301)
            .header(
                    "Location",
                    equalTo(
                            "/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/"
                    )
            );
        as(admin)
            .get("/admin/api/redirects?search=old")
            .then()
            .statusCode(200)
            .body("total", equalTo(1))
            .body("items[0].from", equalTo("/old-page/"))
            .body("items[0].to", equalTo(POST))
            .body("items[0].status", equalTo(301));
    }

    @Test
    void absoluteTargetIsServed() {
        add(admin, "/away/", "https://example.org/x", 302).then().statusCode(201);
        given()
            .redirects()
            .follow(false)
            .get("/away/")
            .then()
            .statusCode(302)
            .header("Location", equalTo("https://example.org/x"));
    }

    @Test
    void deleteRemovesIt() {
        add(admin, "/old-page/", "/new-page/", 301).then().statusCode(201);
        as(admin).queryParam("from", "/old-page/").delete("/admin/api/redirects").then().statusCode(204);
        assertThat(redirects.find("/old-page/")).isEmpty();
        given().redirects().follow(false).get("/old-page/").then().statusCode(404);
    }

    @Test
    void anonymousIs401AndEditorIs403OnEveryEndpoint() {
        for (var call :
                List.of(
                        List.of("GET", "/admin/api/redirects"),
                        List.of("POST", "/admin/api/redirects"),
                        List.of("DELETE", "/admin/api/redirects?from=/x/")
        )) {
            AuthMatrix.anonymousIs401(call.get(0), call.get(1));
            AuthMatrix.forbiddenFor(editor, call.get(0), call.get(1));
        }
        assertThat(redirects.find("/old-page/")).isEmpty();
    }

    @Test
    void writeWithoutCsrfTokenIs400() {
        AuthMatrix.missingCsrfIs400(admin, "POST", "/admin/api/redirects");
        AuthMatrix.missingCsrfIs400(admin, "DELETE", "/admin/api/redirects?from=/x/");
    }

    private static io.restassured.response.Response add(String cookie, String from, String to, int status) {
        return as(cookie).body(Map.of("from", from, "to", to, "status", status)).post("/admin/api/redirects");
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
