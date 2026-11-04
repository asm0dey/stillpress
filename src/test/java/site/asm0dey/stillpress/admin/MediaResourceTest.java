package site.asm0dey.stillpress.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.db.Tables.MEDIA;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
class MediaResourceTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    DSLContext db;
    String contributor;

    @BeforeEach
    void reset() {
        store.reset();
        contributor = sessions.create(users.create("c", "c@example.org", "C", Role.CONTRIBUTOR).getId());
    }

    @Test
    void refusedOembedIs404ForAnyRole() {
        given()
            .cookie("sp_session", contributor)
            .queryParam("url", "http://169.254.169.254/")
            .get("/admin/api/oembed")
            .then()
            .statusCode(404)
            .body("code", equalTo("oembed_invalid_url"));
    }

    @Test
    void mediaById() {
        long id = db
            .insertInto(
                    MEDIA,
                    MEDIA.SOURCE_URL,
                    MEDIA.PATH,
                    MEDIA.MIME_TYPE,
                    MEDIA.WIDTH,
                    MEDIA.HEIGHT,
                    MEDIA.TITLE,
                    MEDIA.ALT_TEXT
            )
            .values("https://old.example/a.jpg", "/media/2024/01/a.jpg", "image/jpeg", 640, 480, "A", "an a")
            .returning(MEDIA.ID)
            .fetchSingle(MEDIA.ID);
        given()
            .cookie("sp_session", contributor)
            .get("/admin/api/media/" + id)
            .then()
            .statusCode(200)
            .body("url", equalTo("/media/2024/01/a.jpg"))
            .body("mimeType", equalTo("image/jpeg"))
            .body("width", equalTo(640))
            .body("height", equalTo(480))
            .body("title", equalTo("A"))
            .body("alt", equalTo("an a"));
        given()
            .cookie("sp_session", contributor)
            .get("/admin/api/media/" + (id + 1))
            .then()
            .statusCode(404)
            .body("error", equalTo("not_found"));
    }
}
