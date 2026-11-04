package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
class PublishResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    DataDir dataDir;

    @BeforeEach
    void reset() throws Exception {
        store.reset();
        store.importSample();
    }

    @Test
    void administratorStartsFullRepublish() {
        var admin = cookie("admin", Role.ADMINISTRATOR);
        as(admin).post("/admin/api/publish").then().statusCode(202).body("state", notNullValue());
        var state = poll(admin, s -> "idle".equals(s.getString("state")) && s.getString("lastPublishedAt") != null);
        assertThat(state.getString("state")).isEqualTo("idle");
        assertThat(Instant.parse(state.getString("lastPublishedAt"))).isAfter(Instant.now().minusSeconds(60));
        assertThat(dataDir.current().resolve("index.html")).exists();
    }

    @Test
    void editorCannotStartIt() {
        as(cookie("editor", Role.EDITOR)).post("/admin/api/publish").then().statusCode(403);
    }

    @Test
    void anyRoleSeesState() {
        as(cookie("contributor", Role.CONTRIBUTOR))
            .get("/admin/api/publish")
            .then()
            .statusCode(200)
            .body("state", equalTo("idle"));
    }

    private JsonPath poll(String cookie, Predicate<JsonPath> done) {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        var s = as(cookie).get("/admin/api/publish").jsonPath();
        while (!done.test(s) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            s = as(cookie).get("/admin/api/publish").jsonPath();
        }
        return s;
    }

    private String cookie(String login, Role role) {
        return sessions.create(users.create(login, login + "@example.org", login, role).getId());
    }

    private static RequestSpecification as(String cookie) {
        return csrf().cookie("sp_session", cookie);
    }
}
