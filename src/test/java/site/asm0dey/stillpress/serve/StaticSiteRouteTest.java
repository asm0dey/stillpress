package site.asm0dey.stillpress.serve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static site.asm0dey.stillpress.db.Tables.REDIRECT;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.publish.Permalinks;
import site.asm0dey.stillpress.publish.SitePublisher;

@QuarkusTest
class StaticSiteRouteTest {
    private static final String CYRILLIC_UPPER =
            "/2023/01/14/%D0%BC%D0%B5%D1%81%D1%8F%D1%86-%D0%BD%D0%BE%D1%8F%D0%B1%D1%80%D1%8C-%D0%B3%D0%BE%D0%B4-2022/";
    @Inject
    TestStore store;
    @Inject
    SitePublisher publisher;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    @TestHTTPResource("/")
    URL base;

    @BeforeEach
    void publish() throws Exception {
        store.reset();
        store.importSample();
        publisher.publishAll();
    }

    @Test
    void servesCyrillicPathUpperAndLowerHex() {
        // urlEncodingEnabled(false): by default RestAssured re-encodes '%' and the server would see %25D0...
        given().urlEncodingEnabled(false).get(CYRILLIC_UPPER).then().statusCode(200);
        given()
            .urlEncodingEnabled(false)
            .get(
                    "/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/"
            )
            .then()
            .statusCode(200);
    }

    @Test
    void rawUtf8DecodesLikeEitherHexCase() {
        // the request line reaches Vert.x as ISO-8859-1 chars, one per byte
        var raw = new String(
                "/2023/01/14/месяц-ноябрь-год-2022/".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1
        );
        var expected = "/2023/01/14/месяц-ноябрь-год-2022/";
        assertThat(StaticSiteRoute.decodePath(raw)).isEqualTo(expected);
        assertThat(StaticSiteRoute.decodePath(CYRILLIC_UPPER)).isEqualTo(expected);
        assertThat(StaticSiteRoute.decodePath(CYRILLIC_UPPER.toLowerCase(Locale.ROOT))).isEqualTo(expected);
    }

    @Test
    void onlyAsciiHexDigitsDecode() {
        for (var bad : List.of("/%１２/", "/%١٢/", "/%g0/", "/%0/", "/%")) {
            assertThatIllegalArgumentException()
                .as(bad)
                .isThrownBy(() -> StaticSiteRoute.decodePath(bad));
        }
        assertThat(StaticSiteRoute.decodePath("/%4a%4A/")).isEqualTo("/JJ/");
    }

    @Test
    void rawUtf8RequestLineIsRejectedByQuarkus() throws Exception {
        // Quarkus checks every request URI with java.net.URI before routing (unless -Dvertx.disableURIValidation);
        // UTF-8 continuation bytes 0x80-0x9f arrive as C1 controls, so the raw form never reaches the route
        assertThat(raw("GET", "/2023/01/14/месяц-ноябрь-год-2022/")).startsWith("HTTP/1.1 400");
    }

    @Test
    void missingTrailingSlashRedirects() {
        given().redirects().follow(false).get("/about").then().statusCode(301).header("Location", endsWith("/about/"));
        given().get("/about/").then().statusCode(200).contentType(startsWith("text/html"));
        given().get("/about/index.html").then().statusCode(200);
    }

    @Test
    void trailingSlashRedirectKeepsQuery() {
        given()
            .redirects()
            .follow(false)
            .urlEncodingEnabled(false)
            .get("/about?utm=x&y=1")
            .then()
            .statusCode(301)
            .header("Location", endsWith("/about/?utm=x&y=1"));
    }

    @Test
    void shortlinkRedirects() {
        given()
            .redirects()
            .follow(false)
            .get("/?p=5994")
            .then()
            .statusCode(301)
            .header(
                    "Location",
                    endsWith(
                            "/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/"
                    )
            );
    }

    @Test
    void unknownShortlinkIs404() {
        given().redirects().follow(false).get("/?p=424242").then().statusCode(404).body(containsString("<html"));
    }

    @Test
    void otherQueryParamsAreIgnored() {
        given().get("/about/?utm_source=x").then().statusCode(200);
    }

    @Test
    void attachmentPageRedirectsToParent() {
        given()
            .redirects()
            .follow(false)
            .get("/2020/05/10/qa/c/")
            .then()
            .statusCode(301)
            .header("Location", endsWith("/2020/05/10/qa/"));
    }

    @Test
    void feedContentType() {
        given()
            .get("/feed/")
            .then()
            .statusCode(200)
            .contentType(startsWith("application/rss+xml"))
            .header("Cache-Control", "public, max-age=300");
    }

    @Test
    void htmlCachedBriefly() {
        given().get("/about/").then().header("Cache-Control", "public, max-age=300");
    }

    @Test
    void mediaAndAssetsCachedForever() throws Exception {
        var media = dataDir.media().resolve("2023/01/a.jpg");
        Files.createDirectories(media.getParent());
        Files.writeString(media, "img");
        publisher.publishAll();
        var forever = "public, max-age=31536000, immutable";
        given().get("/media/2023/01/a.jpg").then().statusCode(200).header("Cache-Control", forever);
        given().get("/assets/plain.css").then().statusCode(200).header("Cache-Control", forever);
    }

    @Test
    void headWorks() {
        given().head("/about/").then().statusCode(200).body(emptyString());
    }

    @Test
    void otherMethodsAreNotServed() {
        given().post("/about/").then().statusCode(anyOf(is(404), is(405)));
    }

    @Test
    void unknownIs404Page() {
        given().get("/nope/").then().statusCode(404).body(containsString("<html"));
    }

    @Test
    void noReleaseYetIsPlain404() throws Exception {
        Files.delete(dataDir.current());
        given().get("/about/").then().statusCode(404);
        given().get("/").then().statusCode(404);
    }

    @Test
    void traversalRejected() {
        given().urlEncodingEnabled(false).get("/%2e%2e/%2e%2e/etc/passwd").then().statusCode(anyOf(is(400), is(404)));
    }

    @Test
    void traversalVariantsRejected() throws Exception {
        assertThat(raw("GET", "/../../etc/passwd")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET", "/%2E%2E/%2e%2E/etc/passwd")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET", "/about/..%2f..%2f..%2fetc/passwd")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET", "/about/..%5c..%5cetc/passwd")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET", "/about\\..\\..\\etc/passwd")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET", "/about/%00/")).startsWith("HTTP/1.1 400");
        assertThat(raw("GET", "/about/%zz/")).startsWith("HTTP/1.1 400");
        // decoded exactly once: %252e is the literal name "%2e", which does not exist
        assertThat(raw("GET", "/%252e%252e/%252e%252e/etc/passwd")).startsWith("HTTP/1.1 404");
    }

    @Test
    void neverRedirectsProtocolRelative() throws Exception {
        assertThat(raw("GET", "//about")).doesNotContainIgnoringCase("location: //");
        assertThat(raw("GET", "/%2fabout")).startsWith("HTTP/1.1 400");
    }

    @Test
    void emptySegmentsNeverServeOrRedirect() throws Exception {
        var current = dataDir.current().toAbsolutePath().normalize();
        var real = current.toRealPath();
        for (var target :
                List.of(
                        "/" + Permalinks.encode(current + "/about"),
                        "/" + Permalinks.encode(current + "/feed/"),
                        "/" + Permalinks.encode(real + "/about"),
                        "/" + Permalinks.encode(real + "/feed/"),
                        "/about//x",
                        "/about//",
                        "//evil.com/",
                        "//about/"
        )) {
            var head = raw("GET", target);
            assertThat(head).as(target).matches("(?s)HTTP/1.1 40[04] .*").doesNotContainIgnoringCase("location:");
        }
    }

    @Test
    void unsafeStoredRedirectTargetsAre404() {
        db
            .insertInto(REDIRECT, REDIRECT.FROM_PATH, REDIRECT.TO_PATH, REDIRECT.STATUS_CODE)
            .values("/evil1/", "//evil.com/", 301)
            .values("/evil2/", "https://evil.com/", 301)
            .values("/evil3/", "/\\evil.com/", 301)
            .values("?p=777", "//evil.com/", 301)
            .execute();
        for (var u : List.of("/evil1/", "/evil2/", "/evil3/", "/?p=777")) {
            given().redirects().follow(false).get(u).then().statusCode(404).header("Location", nullValue());
        }
    }

    @Test
    void storedStatusIsReturnedAsIs() {
        db
            .insertInto(REDIRECT, REDIRECT.FROM_PATH, REDIRECT.TO_PATH, REDIRECT.STATUS_CODE)
            .values("/moved/", "/about/", 302)
            .execute();
        given().redirects().follow(false).get("/moved/").then().statusCode(302).header("Location", endsWith("/about/"));
    }

    @Test
    void xmlContentTypeAndNosniff() throws Exception {
        Files.writeString(dataDir.current().resolve("sitemap.xml"), "<urlset/>");
        given()
            .get("/sitemap.xml")
            .then()
            .statusCode(200)
            .contentType(anyOf(startsWith("application/xml"), startsWith("text/xml")))
            .header("X-Content-Type-Options", "nosniff");
        given().get("/about/").then().header("X-Content-Type-Options", "nosniff");
        given().get("/nope/").then().statusCode(404).header("X-Content-Type-Options", "nosniff");
    }

    @Test
    void symlinkOutOfReleaseNotFollowed() throws Exception {
        var outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("index.html"), "<html>secret</html>");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        var release = dataDir.current().toRealPath();
        Files.createSymbolicLink(release.resolve("evil"), outside);
        Files.createSymbolicLink(release.resolve("evil.txt"), outside.resolve("secret.txt"));
        given().get("/evil/").then().statusCode(404).body(containsString("<html")).body(not(containsString("secret")));
        given().get("/evil/secret.txt").then().statusCode(404);
        given().get("/evil.txt").then().statusCode(404);
    }

    /**
     * Sends the request target byte for byte (UTF-8), bypassing client-side normalisation; returns the response head.
     */
    private String raw(String method, String target) throws IOException {
        try (var s = new Socket(base.getHost(), base.getPort())) {
            var out = s.getOutputStream();
            out.write((method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8)
            );
            out.flush();
            var head = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            var end = head.indexOf("\r\n\r\n");
            return end < 0 ? head : head.substring(0, end);
        }
    }
}
