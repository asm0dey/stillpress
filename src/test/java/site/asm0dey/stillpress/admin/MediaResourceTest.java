package site.asm0dey.stillpress.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.MEDIA;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;
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
    @Inject
    DataDir dataDir;
    @Inject
    SettingRepository settings;
    String contributor;
    String author;

    @BeforeEach
    void reset() {
        store.reset();
        contributor = sessions.create(users.create("c", "c@example.org", "C", Role.CONTRIBUTOR).getId());
        author = sessions.create(users.create("a", "a@example.org", "A", Role.AUTHOR).getId());
    }

    @Test
    void uploadIsStoredListedAndPublished() throws IOException {
        var folder = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM"));
        int id = upload(author, "My Photo.PNG", png(3, 2))
            .then()
            .statusCode(201)
            .body("url", equalTo("/media/" + folder + "/my-photo.png"))
            .body("mimeType", equalTo("image/png"))
            .body("width", equalTo(3))
            .body("height", equalTo(2))
            .extract()
            .path("id");
        var published = dataDir.current().resolve("media/" + folder + "/my-photo.png");
        TestStore.await(() -> Files.exists(published), 30);
        assertThat(published).exists();
        given()
            .cookie("sp_session", contributor)
            .get("/admin/api/media")
            .then()
            .statusCode(200)
            .body("total", equalTo(1))
            .body("items[0].id", equalTo(id));
        given().cookie("sp_session", contributor).get("/admin/api/media?type=audio").then().body("total", equalTo(0));
        given().cookie("sp_session", contributor).get("/admin/api/media?search=photo").then().body("total", equalTo(1));
        csrf()
            .cookie("sp_session", author)
            .contentType("application/json")
            .body(Map.of("title", "T", "alt", "an a"))
            .put("/admin/api/media/" + id)
            .then()
            .statusCode(200)
            .body("title", equalTo("T"))
            .body("alt", equalTo("an a"));
        csrf()
            .cookie("sp_session", author)
            .contentType("application/json")
            .body(Map.of("title", "T"))
            .put("/admin/api/media/" + (id + 1))
            .then()
            .statusCode(404);
    }

    @Test
    void svgAndHtmlRefused() throws IOException {
        for (var name : List.of("x.svg", "x.html", "noextension")) {
            upload(author, name, new byte[] {1}).then().statusCode(400).body("error", equalTo("type_not_allowed"));
        }
        try (var files = Files.walk(dataDir.media())) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void traversalNameIsFlattened() throws IOException {
        var folder = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM"));
        upload(author, "../../evil.png", png(1, 1))
            .then()
            .statusCode(201)
            .body("url", equalTo("/media/" + folder + "/evil.png"));
        assertThat(dataDir.media().resolve(folder).resolve("evil.png")).exists();
    }

    @Test
    void clashGetsSuffix() throws IOException {
        upload(author, "a.png", png(1, 1)).then().statusCode(201).body("url", org.hamcrest.Matchers.endsWith("/a.png"));
        upload(author, "a.png", png(1, 1)).then().statusCode(201).body(
                "url",
                org.hamcrest.Matchers.endsWith("/a-2.png")
        );
    }

    @Test
    void parallelUploadsOfOneNameKeepTheirOwnBytes() throws Exception {
        // the first-ever concurrent write races inside ImportGuard.close(); settle it so this test is about names
        settings.put(ImportGuard.STATE, "closed");
        int n = 6;
        var pool = Executors.newFixedThreadPool(n);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < n; i++) {
                var bytes = ("content-" + i).getBytes(StandardCharsets.UTF_8);
                futures.add(pool.submit(() -> {
                    var url = upload(author, "image.png", bytes).then().statusCode(201).extract().<String>path("url");
                    return url + "|" + new String(bytes, StandardCharsets.UTF_8);
                }));
            }
            var urls = new HashSet<String>();
            for (var f : futures) {
                var parts = f.get(60, TimeUnit.SECONDS).split("\\|");
                assertThat(urls.add(parts[0])).as(parts[0]).isEqualTo(true);
                assertThat(Files.readString(dataDir.root().resolve(parts[0].substring(1)))).isEqualTo(parts[1]);
            }
            assertThat(urls).hasSize(n);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void contributorIs403() throws IOException {
        upload(contributor, "a.png", png(1, 1)).then().statusCode(403);
        assertThat(db.fetchCount(MEDIA)).isZero();
    }

    @Test
    void uploadAboveJsonLimitIsAccepted() {
        upload(author, "big.mp3", new byte[2 * 1024 * 1024]).then().statusCode(201).body(
                "mimeType",
                equalTo("audio/mpeg")
        );
    }

    @Test
    void uploadClosesImport() throws IOException {
        upload(author, "a.png", png(1, 1)).then().statusCode(201);
        assertThat(db.fetchValue(
                site.asm0dey.stillpress.db.Tables.SETTING.VALUE,
                site.asm0dey.stillpress.db.Tables.SETTING.KEY.eq("import_state")
        ))
            .isEqualTo("closed");
    }

    private io.restassured.response.Response upload(String session, String name, byte[] bytes) {
        return csrf()
            .cookie("sp_session", session)
            .multiPart("file", name, bytes, "application/octet-stream")
            .post("/admin/api/media");
    }

    private static byte[] png(int w, int h) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
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
