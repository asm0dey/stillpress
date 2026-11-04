package site.asm0dey.stillpress.admin;

import static site.asm0dey.stillpress.Csrf.csrf;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static io.restassured.matcher.RestAssuredMatchers.detailedCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class SetupResourceTest {
    @Inject
    TestStore store;
    @Inject
    SetupToken token;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    DataDir dataDir;

    @BeforeEach
    void reset() {
        store.reset();
        token.regenerate();
    }

    @Test
    void requiredUntilAdministratorExists() {
        given().get("/admin/api/setup").then().body("required", is(true));
        setup(token.current().orElseThrow()).then().statusCode(201).cookie("sp_session");
        given().get("/admin/api/setup").then().body("required", is(false));
        assertThat(token.current()).isEmpty();
    }

    @Test
    void sessionCookieLogsTheAdministratorIn() {
        var cookie = setup(token.current().orElseThrow()).then().statusCode(201).extract().cookie("sp_session");
        given()
            .cookie("sp_session", cookie)
            .get("/admin/api/site")
            .then()
            .statusCode(200)
            .body("title", equalTo("My Site"))
            .body("language", equalTo("en-GB"))
            .body("homeUrl", equalTo("http://localhost:8081"))
            .body("items", is(0))
            .body("comments", is(0));
        given().get("/admin/api/site").then().statusCode(401);
    }

    @Test
    void homeUrlAndSecureCookieFollowTheForwardedRequest() {
        csrf()
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "blog.example.org")
            .contentType(JSON)
            .body(body(token.current().orElseThrow(), "admin0"))
            .post("/admin/api/setup")
            .then()
            .statusCode(201)
            .cookie("sp_session", detailedCookie().secured(true).httpOnly(true));
        assertThat(settings.get("home_url")).hasValue("https://blog.example.org");
    }

    @Test
    void keepsAnImportedHomeUrl() {
        settings.put("home_url", "https://blog.example.ru");
        csrf()
            .header("X-Forwarded-Host", "tunnel.example.net")
            .contentType(JSON)
            .body(body(token.current().orElseThrow(), "admin0"))
            .post("/admin/api/setup")
            .then()
            .statusCode(201);
        assertThat(settings.get("home_url")).hasValue("https://blog.example.ru");
    }

    @Test
    void writesSettingsAndKeepsExistingDefaults() {
        settings.put("posts_per_page", "25");
        setup(token.current().orElseThrow()).then().statusCode(201);
        assertThat(settings.get("posts_per_page")).hasValue("25");
        assertThat(settings.get("posts_per_rss")).hasValue("10");
        assertThat(settings.get("category_base")).hasValue("category");
        assertThat(settings.get("tag_base")).hasValue("tag");
    }

    @Test
    void upgradesAnExistingRowWithThatLogin() {
        var id = users.create("admin0", "old@example.org", "Imported", Role.AUTHOR).getId();
        setup(token.current().orElseThrow()).then().statusCode(201);
        var u = users.findById(id).orElseThrow();
        assertThat(u.getRole()).isEqualTo(Role.ADMINISTRATOR.id());
        assertThat(Passwords.verify("correct horse battery", u.getPasswordHash())).isTrue();
        assertThat(users.all()).hasSize(1);
    }

    @Test
    void upgradeKeepsTypedEmailAndDisplayName() {
        var id = users.create("admin0", "old@example.org", "Imported", Role.AUTHOR).getId();
        setup(token.current().orElseThrow()).then().statusCode(201);
        var u = users.findById(id).orElseThrow();
        assertThat(List.of(u.getEmail(), u.getDisplayName())).containsExactly("admin0@example.org", "Admin");
    }

    @Test
    void wrongTokenIs403() {
        setup("not-the-token").then().statusCode(403).body("error", equalTo("bad_token"));
    }

    @Test
    void secondSetupIs409EvenWithTheToken() {
        var t = token.current().orElseThrow();
        setup(t).then().statusCode(201);
        setup(t).then().statusCode(409).body("error", equalTo("already_set_up"));
    }

    @Test
    void concurrentSetupsCreateOneAdministrator() {
        var t = token.current().orElseThrow();
        var codes = IntStream
            .range(0, 8)
            .parallel()
            .mapToObj(i -> setup(t, "admin" + i).statusCode())
            .toList();
        assertThat(codes).containsOnlyOnce(201);
        assertThat(users.countAdministrators()).isEqualTo(1);
    }

    @Test
    void setupPublishesAnEmptySite() {
        setup(token.current().orElseThrow()).then().statusCode(201);
        TestStore.await(() -> Files.exists(dataDir.current().resolve("index.html")), 20);
        assertThat(dataDir.current().resolve("index.html")).exists();
    }

    @Test
    void badLanguageIs400() {
        var body = body(token.current().orElseThrow(), "admin0");
        body.put("language", "Russian");
        post(body).then().statusCode(400).body("error", equalTo("invalid")).body("field", equalTo("language"));
        assertThat(token.current()).isPresent();
    }

    @Test
    void timezoneIsStored() {
        var body = body(token.current().orElseThrow(), "admin0");
        body.put("timezone", "Europe/Moscow");
        post(body).then().statusCode(201);
        assertThat(settings.get("timezone")).hasValue("Europe/Moscow");
    }

    @Test
    void badTimezoneIs400() {
        var body = body(token.current().orElseThrow(), "admin0");
        body.put("timezone", "Mars/Base");
        post(body).then().statusCode(400).body("field", equalTo("timezone"));
    }

    @Test
    void weakPasswordIs400() {
        var body = body(token.current().orElseThrow(), "admin0");
        body.put("password", "short");
        post(body).then().statusCode(400).body("field", equalTo("password"));
    }

    @Test
    void startEmptyThenImportIs409() {
        var cookie = setup(token.current().orElseThrow()).then().statusCode(201).extract().cookie("sp_session");
        csrf().cookie("sp_session", cookie).post("/admin/api/setup/start-empty").then().statusCode(204);
        upload(cookie).then().statusCode(409).body("error", equalTo("already_imported"));
        csrf().cookie("sp_session", cookie).get("/admin/api/site").then().body("importAllowed", is(false));
    }

    @Test
    void startEmptyNeedsAnAdministrator() {
        csrf().post("/admin/api/setup/start-empty").then().statusCode(401);
    }

    @Test
    void userCreatedBeforeImportClosesIt() {
        var cookie = setup(token.current().orElseThrow()).then().statusCode(201).extract().cookie("sp_session");
        csrf()
            .cookie("sp_session", cookie)
            .contentType(JSON)
            .body(Map.of("login", "w", "email", "w@example.org", "displayName", "W", "role", "author"))
            .post("/admin/api/users")
            .then()
            .statusCode(201);
        upload(cookie).then().statusCode(409);
    }

    @Test
    void importStillAllowedRightAfterSetup() throws InterruptedException {
        var cookie = setup(token.current().orElseThrow()).then().statusCode(201).extract().cookie("sp_session");
        csrf().cookie("sp_session", cookie).get("/admin/api/site").then().body("importAllowed", is(true));
        upload(cookie).then().statusCode(202);
        var deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)
                && csrf().cookie("sp_session", cookie).get("/admin/api/import").path("state").equals("running")) {
            Thread.sleep(100);
        }
    }

    private static Response upload(String cookie) {
        try (var in = TestStore.sample()) {
            return csrf()
                .cookie("sp_session", cookie)
                .multiPart("file", "export.xml", in.readAllBytes(), "text/xml")
                .post("/admin/api/import");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Response setup(String token) {
        return setup(token, "admin0");
    }

    static Response setup(String token, String login) {
        return post(body(token, login));
    }

    private static Map<String, String> body(String token, String login) {
        return new HashMap<>(Map.of(
                "token",
                token,
                "title",
                "My Site",
                "language",
                "en-GB",
                "login",
                login,
                "email",
                login + "@example.org",
                "displayName",
                "Admin",
                "password",
                "correct horse battery"
        ));
    }

    private static Response post(Map<String, String> body) {
        return csrf().contentType(JSON).body(body).post("/admin/api/setup");
    }
}
