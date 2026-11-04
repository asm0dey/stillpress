package site.asm0dey.stillpress.serve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.SitePublisher;

/**
 * Mode 2 end to end: a real nginx serves the release from the generated include and falls back to this app.
 */
@QuarkusTest
class NginxModeTwoTest {
    private static GenericContainer<?> nginx;
    @Inject
    TestStore store;
    @Inject
    SitePublisher publisher;
    @Inject
    DataDir dataDir;
    @TestHTTPResource("/")
    URL app;

    @BeforeEach
    void publish() throws Exception {
        store.reset();
        store.importSample();
        publisher.publishAll();
        if (nginx == null) {
            Testcontainers.exposeHostPorts(app.getPort());
            var include =
                    NginxConfig.render(Path.of("/site/output/current"), "host.testcontainers.internal:"
                    + app.getPort());
            nginx = new GenericContainer<>("nginx:1.29-alpine")
                .withFileSystemBind(dataDir.output().toString(), "/site/output", BindMode.READ_ONLY)
                .withCopyToContainer(
                        Transferable.of("server {\n    listen 80;\n" + include + "}\n"),
                        "/etc/nginx/conf.d/default.conf"
                )
                // a broken include fails the start with nginx's own message in the container log
                .withCommand("sh", "-c", "nginx -t && exec nginx -g 'daemon off;'")
                .withExposedPorts(80);
            nginx.start();
        }
    }

    @AfterAll
    static void stop() {
        if (nginx != null) {
            nginx.stop();
            nginx = null;
        }
    }

    @Test
    void servesCyrillicPathsInBothHexCases() {
        get("/2023/01/14/%D0%BC%D0%B5%D1%81%D1%8F%D1%86-%D0%BD%D0%BE%D1%8F%D0%B1%D1%80%D1%8C-%D0%B3%D0%BE%D0%B4-2022/")
            .then()
            .statusCode(200);
        get("/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/")
            .then()
            .statusCode(200);
    }

    @Test
    void feedIsRss() {
        get("/feed/")
            .then()
            .statusCode(200)
            .contentType(startsWith("application/rss+xml"))
            .header("Cache-Control", StaticSiteRoute.SHORT_CACHE);
    }

    @Test
    void unknownPathGetsStillpress404() {
        get("/nope/").then().statusCode(404).body(containsString("<html"));
    }

    @Test
    void directoryWithoutIndexGetsStillpress404() {
        get("/assets/").then().statusCode(404).body(containsString("<html"));
    }

    @Test
    void assetsCachedForever() {
        get("/assets/plain.css").then().statusCode(200).header("Cache-Control", StaticSiteRoute.LONG_CACHE);
    }

    @Test
    void shortlinkReachesRedirectMap() {
        get("/?p=5994").then().statusCode(301).header("Location", containsString("/2023/01/14/"));
    }

    @Test
    void missingSlashRedirectsLikeWordPress() {
        get("/about").then().statusCode(301).header("Location", endsWith("/about/"));
    }

    @Test
    void adminIsProxied() {
        get("/admin/api/setup").then().statusCode(200).contentType(startsWith("application/json"));
    }

    @Test
    void publishSwapIsPickedUpWithoutReload() throws Exception {
        var old = dataDir.current().toRealPath();
        publisher.publishAll();
        var fresh = dataDir.current().toRealPath();
        assertThat(fresh).isNotEqualTo(old);
        Files.writeString(fresh.resolve("release.txt"), fresh.getFileName().toString());
        get("/release.txt").then().statusCode(200).body(is(fresh.getFileName().toString()));
        get("/").then().statusCode(200);
    }

    private static Response get(String path) {
        return given()
            .baseUri("http://" + nginx.getHost())
            .port(nginx.getMappedPort(80))
            .urlEncodingEnabled(false)
            .redirects()
            .follow(false)
            .get(path);
    }
}
