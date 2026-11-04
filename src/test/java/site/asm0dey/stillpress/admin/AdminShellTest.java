package site.asm0dey.stillpress.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;

@QuarkusTest
class AdminShellTest {
    @Inject
    TestStore store;
    @Inject
    SetupToken token;

    @Test
    void spaServedForClientRoutes() {
        for (var path : new String[] {"/admin/", "/admin/setup", "/admin/users", "/admin/posts", "/admin/edit/1"}) {
            given()
                .get(path)
                .then()
                .statusCode(200)
                .contentType(startsWith("text/html"))
                .body(containsString("<div id=\"root\""));
        }
    }

    @Test
    void shellIsRevalidatedButAssetsStayCached() {
        // a cached shell after an upgrade points at bundles that are gone
        for (var path : new String[] {"/admin/", "/admin/setup"}) {
            given().get(path).then().statusCode(200).header("Cache-Control", "no-cache");
        }
        var html = given().get("/admin/").asString();
        var asset = html.replaceFirst("(?s).*src=\"(/admin/assets/[^\"]+\\.js)\".*", "$1");
        // prod marks them immutable; the test profile sends no Cache-Control for static files
        given().get(asset).then().statusCode(200).header("Cache-Control", not("no-cache"));
    }

    @Test
    void adminWithoutSlashRedirects() {
        given().redirects().follow(false).get("/admin").then().statusCode(302).header("Location", "/admin/");
        given()
            .redirects()
            .follow(false)
            .urlEncodingEnabled(false)
            .get("/admin?x=1")
            .then()
            .statusCode(302)
            .header("Location", "/admin/?x=1");
    }

    @Test
    void apiIsNotSwallowedBySpaRouting() {
        given().get("/admin/api/setup").then().statusCode(200).contentType(startsWith("application/json"));
        // an unknown API path is a real 404, not the SPA shell
        given().get("/admin/api/no-such-thing").then().statusCode(404).body(not(containsString("id=\"root\"")));
    }

    @Test
    void siteRouteStillServesRoot() {
        store.reset();
        token.regenerate();
        SetupResourceTest.setup(token.current().orElseThrow()).then().statusCode(201);
        // the site is published in the background
        TestStore.await(() -> given().get("/").statusCode() == 200, 20);
        given().get("/").then().statusCode(200).contentType(startsWith("text/html")).body(containsString("My Site"));
    }
}
