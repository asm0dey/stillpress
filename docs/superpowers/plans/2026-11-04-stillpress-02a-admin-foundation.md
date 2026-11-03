# Stillpress 02a — Admin Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fresh install opens a token-protected setup wizard at `/admin`, creates the first administrator, then starts empty or imports a WXR upload in the background; users log in with passwords behind rate limiting; nginx serves the published site (Mode 2) from a Stillpress-generated include.

**Architecture:** Same single Quarkus app. Users are rows of the existing `author` table plus role and password hash. Server-side sessions (`session` table, opaque cookie) feed a custom Quarkus `HttpAuthenticationMechanism`, so login, rate limiting and later CAPTCHA/2FA (plan 02c) stay in our code. Admin front end = React SPA built by Bun through Quinoa at `/admin`; its JSON API lives at `/admin/api/*`. Import moves from CLI-only to a background job with a shared "import at most once" guard. A `nginx-config` CLI command prints the Mode 2 include.

**Tech Stack:** existing stack (Java 25, Quarkus 3.40.1.1, jOOQ, Flyway, H2/PostgreSQL, Qute) plus `quarkus-security`, `io.quarkiverse.quinoa:quarkus-quinoa:2.9.3`, `com.password4j:password4j:1.8.4` (Argon2id), Bun 1.4.4, React 19.3.1, react-router 8.5.1, Vite 8.3.5, `@vitejs/plugin-react` 6.2.1; tests add `org.testcontainers:testcontainers:${testcontainers.version}` (nginx container).

**Spec:** `docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md` (+ `CONTEXT.md`, `docs/adr/0003`, `docs/adr/0004`, `docs/superpowers/plans/ROADMAP.md`)

**This is plan 02a of 02a/02b/02c.** 02b: editor and publishing workflow (Gutenberg, WP REST slice, statuses, incremental/background republish, preview, media library, reusable blocks, revisions, admin i18n). 02c: TOTP, passkeys, CAPTCHA, SMTP reset/invite/notifications.

## Global Constraints

- Everything in plan 01's Global Constraints still holds (package root, mise, data dir, portable Flyway SQL in `db/migration/common`, jOOQ codegen via `-Pcodegen`, byte-identical permalinks, atomic publish, use the `quarkus-agent` MCP to drive the app).
- Work on `main`; push every commit to `origin` (standing instruction for this repo).
- Admin lives at **`/admin`** on the site's own domain; `/wp-admin`, `/wp-admin/*` and `/wp-login.php` answer **302 → `/admin/`** (spec §6.2 item 10).
- Admin JSON API prefix: **`/admin/api/`**. Every state-changing request (`POST|PUT|PATCH|DELETE`) under `/admin/api/` must carry header **`X-Requested-With: stillpress`**, else 403 (CSRF guard; no CORS is enabled, so browsers cannot send it cross-site).
- Session cookie: name **`sp_session`**, 32 random bytes base64url, `HttpOnly; SameSite=Lax; Path=/`, plus `Secure` when the request scheme (after proxy forwarding) is `https`. Server stores only its SHA-256 hex. Lifetime **14 days** absolute.
- Passwords: **Argon2id** via password4j, PHC string stored in `author.password_hash`; length **12–1024** characters. Imported users have `password_hash = NULL` and cannot log in until they set one (spec §6.2.2).
- Roles (exact strings): `administrator`, `editor`, `author`, `contributor`. Imported authors get `author`. Only `administrator` may manage users and import.
- Login rate limits (in memory, one install = one process): **per account 5 failures / 15 min**, **per client IP 20 failures / 15 min**; over the limit → **429** with `Retry-After` seconds, even for a correct password. Failure message is the same for unknown login and wrong password.
- Client IP comes from forwarded headers only via Quarkus proxy settings: `quarkus.http.proxy.proxy-address-forwarding=true`, `quarkus.http.proxy.allow-x-forwarded=true`, `quarkus.http.proxy.enable-forwarded-host=true`, `quarkus.http.proxy.trusted-proxies=${STILLPRESS_TRUSTED_PROXIES:127.0.0.1,::1}`.
- Setup token: 24 random bytes base64url, generated at startup **only when the HTTP server is enabled and no administrator with a password exists**, logged at INFO as `Setup token: <token> — open /admin to create the site`; compared in constant time; discarded once setup succeeds.
- Set-password tokens: 32 random bytes base64url, SHA-256 stored, single use, valid **24 h**; link form `<home_url>/admin/set-password?token=<token>`.
- Import is at most once (spec §3, §6.2 item 4): one guard used by the wizard and the CLI. Allowed only when `item`, `comment`, `media`, `term`, `reusable_block` are all empty **and** setting `import_state` is absent or `failed`.
- `quarkus.http.limits.max-body-size=1G` (the real blog's WXR is 13.6 MB; plan 02b media uploads need large bodies too).
- Bun is pinned in two places that must stay equal: `mise.toml` `bun = "1.4.4"` and `quarkus.quinoa.package-manager-install.bun-version=1.4.4`; a test enforces it (spec §6.1). npm dependencies are pinned to exact versions; `bun.lock` is committed.
- Admin UI design follows the **ui-ux-pro-max** skill (spec §6.1, §6.2 item 3).
- The nginx include never contains site data and never changes on publish (ADR 0004).

## Review Focus

1. **Wizard after setup / two wizards at once** — once an administrator exists, `POST /admin/api/setup` returns 409 even with the right token; two concurrent setups produce exactly one administrator. Test in Task 3.
2. **Brute force against one account and spread across accounts** — the 6th attempt on one login within 15 min is 429 even with the correct password; 21 failures from one IP across different logins lock that IP. Test in Task 2.
3. **Last administrator demotes themself** — would lock everyone out of user management; must be 409. Test in Task 4.
4. **Import twice, or CLI import while the wizard import runs, or retry after a failed import that left nothing behind** — second start is 409 / exit 1; retry after a clean failure is allowed. Test in Task 5.
5. **Requests through nginx**: upper/lower-case percent-encoded Cyrillic paths, `/feed/` served as `application/rss+xml`, unknown paths get Stillpress's 404 page with status 404, `/?p=N` reaches the redirect map, `/admin` reaches the SPA. Test in Task 6.

---

## File Structure

```
mise.toml                                   + bun = "1.4.4"
pom.xml                                     + quarkus-security, quarkus-quinoa, password4j, testcontainers (test)
src/main/resources/application.properties   + quinoa, proxy, body limit
src/main/resources/db/migration/common/V3__accounts.sql
src/main/java/site/asm0dey/stillpress/
  account/Role.java                 enum of the four roles
  account/Passwords.java            Argon2id hash/verify
  account/UserRepository.java       jOOQ over AUTHOR (users are author rows)
  account/SessionStore.java         session create/find/delete
  account/TokenStore.java           set-password tokens
  account/LoginLimiter.java         per-account / per-IP failure windows
  account/SessionAuthMechanism.java HttpAuthenticationMechanism reading sp_session
  account/SessionIdentityProvider.java + SessionAuthenticationRequest.java
  account/CsrfFilter.java           X-Requested-With guard on /admin/api
  account/UserCommand.java          `stillpress user reset-password <login>`
  admin/AuthResource.java           /admin/api/login, logout, session, set-password
  admin/SetupResource.java          /admin/api/setup
  admin/SetupToken.java             startup token
  admin/UsersResource.java          /admin/api/users
  admin/SiteResource.java           /admin/api/site (dashboard numbers)
  admin/ImportResource.java         /admin/api/import (upload + status)
  admin/LegacyAdminRoutes.java      /wp-admin, /wp-login.php → /admin/
  importer/ImportGuard.java         "at most once" check + import_state
  importer/ImportJob.java           background import + publish, progress
  importer/ImportListener.java      progress callbacks from WordPressImporter
  serve/NginxConfig.java            renders the Mode 2 include
  serve/NginxConfigCommand.java     `stillpress nginx-config`
src/main/resources/templates/nginx/stillpress.conf   Qute template of the include
src/main/webui/                     package.json, bun.lock, vite.config.js, index.html,
                                    src/{main.jsx, api.js, theme.css, pages/*.jsx}
docs/deploy/nginx-site.conf.example
src/test/java/site/asm0dey/stillpress/... mirrors main; TestStore gains account tables
```

---

### Task 1: Accounts schema, passwords, sessions, auth mechanism

**Files:**
- Create: `V3__accounts.sql`, `account/Role.java`, `account/Passwords.java`, `account/UserRepository.java`, `account/SessionStore.java`, `account/TokenStore.java`, `account/SessionAuthenticationRequest.java`, `account/SessionIdentityProvider.java`, `account/SessionAuthMechanism.java`, `account/CsrfFilter.java`
- Modify: `pom.xml` (add `io.quarkus:quarkus-security`, `com.password4j:password4j:1.8.4`), `application.properties` (proxy settings, body limit from Global Constraints), `src/test/java/.../TestStore.java` (`reset()` also deletes `session`, `user_token`)
- Test: `account/PasswordsTest.java`, `account/SessionAuthTest.java`

**Interfaces:**
- Consumes: `AUTHOR` table, `DSLContext`, `Nicename.of(String)`.
- Produces:
  - `V3__accounts.sql`: `ALTER TABLE author ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'author'`; `ALTER TABLE author ADD COLUMN password_hash VARCHAR(255)`; `CREATE TABLE session(id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES author(id), created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL)`; `CREATE TABLE user_token(id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES author(id), purpose VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL)`. Re-run `-Pcodegen`.
  - `enum Role { ADMINISTRATOR, EDITOR, AUTHOR, CONTRIBUTOR; String id() /* lower-case name */; static Role of(String id) /* IllegalArgumentException on unknown */ }`
  - `final class Passwords { static String hash(String password); static boolean verify(String password, String hash /* nullable → false */); static void validate(String password) /* IllegalArgumentException unless 12..1024 chars */ }` — password4j `Password.hash(pw).addRandomSalt(16).withArgon2()` with `Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID)`; verify with `Password.check(pw, hash).with(Argon2Function.getInstanceFromHash(hash))`.
  - `@ApplicationScoped class UserRepository { Optional<AuthorRecord> findByLoginOrEmail(String s); Optional<AuthorRecord> findById(long id); List<AuthorRecord> all(); AuthorRecord create(String login, String email, String displayName, Role role) /* slug = unique Nicename.of(login) */; void setPassword(long id, String hash); void setRole(long id, Role role); boolean anyAdministratorWithPassword(); int countAdministrators(); }` — `findByLoginOrEmail` matches login exactly, else email case-insensitively when exactly one row has it.
  - `@ApplicationScoped class SessionStore { String create(long userId) /* returns raw cookie value */; Optional<AuthorRecord> find(String rawToken) /* null/expired → empty; expired rows deleted */; void delete(String rawToken); }`
  - `@ApplicationScoped class TokenStore { String create(long userId, String purpose, Duration ttl); Optional<Long> consume(String rawToken, String purpose) /* deletes the row; empty if unknown/expired/other purpose */ }` — purpose `"set_password"` in this plan.
  - `SessionAuthMechanism implements HttpAuthenticationMechanism`: reads cookie `sp_session`; no cookie → `Uni.createFrom().nullItem()` (anonymous); else delegates `SessionAuthenticationRequest(token)` to `SessionIdentityProvider`, which (on a worker via `context.runBlocking`) builds a `QuarkusSecurityIdentity` with principal = login, role = `Role.id()`, attribute `userId` (Long). `getChallenge` → 401 without `WWW-Authenticate`.
  - Static helper `SessionAuthMechanism.cookie(String raw, boolean secure) -> io.vertx.core.http.Cookie` and `clearedCookie(boolean secure)` (Max-Age 0) used by Tasks 2–3.
  - `CsrfFilter`: Vert.x route filter (`@RouteFilter`) for `/admin/api/*` enforcing the `X-Requested-With: stillpress` rule from Global Constraints → 403 `text/plain` "Missing X-Requested-With".

- [ ] **Step 1: Write the migration and regenerate jOOQ**

Run: `mise exec -- mvn -q -Pcodegen generate-sources`
Expected: `site.asm0dey.stillpress.db.tables.Session` and `UserToken` exist; `AuthorRecord.getRole()` exists.

- [ ] **Step 2: Write failing tests**

```java
class PasswordsTest {
  @Test void hashIsArgon2idAndVerifies() {
    var h = Passwords.hash("correct horse battery");
    assertThat(h).startsWith("$argon2id$");
    assertThat(Passwords.verify("correct horse battery", h)).isTrue();
    assertThat(Passwords.verify("wrong horse battery", h)).isFalse();
    assertThat(Passwords.verify("anything", null)).isFalse();
  }
  @Test void lengthBounds() {
    assertThatThrownBy(() -> Passwords.validate("short")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Passwords.validate("x".repeat(1025))).isInstanceOf(IllegalArgumentException.class);
    Passwords.validate("twelve chars");
  }
}

// a test-only resource under src/test: @Path("/admin/api/test/whoami") @RolesAllowed("administrator") GET → principal name
@QuarkusTest class SessionAuthTest {
  @Inject TestStore store; @Inject UserRepository users; @Inject SessionStore sessions;
  @BeforeEach void reset() { store.reset(); }
  @Test void validSessionAuthenticates() {
    var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
    var raw = sessions.create(u.getId());
    given().cookie("sp_session", raw).get("/admin/api/test/whoami").then().statusCode(200).body(equalTo("admin"));
  }
  @Test void noOrUnknownCookieIs401() {
    given().get("/admin/api/test/whoami").then().statusCode(401);
    given().cookie("sp_session", "nope").get("/admin/api/test/whoami").then().statusCode(401);
  }
  @Test void wrongRoleIs403() {
    var u = users.create("writer", "w@example.org", "W", Role.AUTHOR);
    given().cookie("sp_session", sessions.create(u.getId())).get("/admin/api/test/whoami").then().statusCode(403);
  }
  @Test void expiredSessionIs401() { /* insert a session row with expires_at in the past */ }
  @Test void postWithoutCsrfHeaderIs403() {
    given().post("/admin/api/logout").then().statusCode(403);
  }
}
```

- [ ] **Step 3: Run tests, verify fail**

Run: `mise exec -- mvn -q test -Dtest='PasswordsTest,SessionAuthTest'`
Expected: FAIL — classes missing.

- [ ] **Step 4: Implement the types above**

- [ ] **Step 5: Run tests, verify pass** — same command → PASS (the logout endpoint arrives in Task 2; until then the CSRF test hits the filter, which runs before routing, so 403 holds).

- [ ] **Step 6: Commit**

```bash
git add pom.xml src
git commit -m "feat: accounts schema, Argon2id passwords, server-side sessions"
git push
```

---

### Task 2: Login, logout, set-password, rate limiting, CLI reset

**Files:**
- Create: `account/LoginLimiter.java`, `admin/AuthResource.java`, `admin/LegacyAdminRoutes.java`, `account/UserCommand.java`
- Modify: `StillpressCommand.java` (register `UserCommand`)
- Test: `account/LoginLimiterTest.java`, `admin/AuthResourceTest.java`, `account/UserCommandTest.java`

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `@ApplicationScoped class LoginLimiter { LoginLimiter(Clock clock) /* CDI: Clock.systemUTC() producer */; Optional<Duration> blocked(String login, String ip) /* time until allowed */; void failure(String login, String ip); void success(String login) }` — sliding 15-minute windows of failure timestamps; limits from Global Constraints; login key lower-cased.
  - `POST /admin/api/login` body `{"login":..,"password":..}` → 204 + `Set-Cookie sp_session`; 401 `{"error":"invalid_credentials"}`; 429 `{"error":"rate_limited"}` + `Retry-After`. Order: limiter check → user lookup → `Passwords.verify` (for an unknown login verify against a fixed dummy Argon2id hash so timing is equal) → failure/success bookkeeping.
  - `POST /admin/api/logout` → 204, deletes the session, clears the cookie (anonymous allowed).
  - `GET /admin/api/session` → 200 `{"id":1,"login":"admin","displayName":"Admin","role":"administrator"}` or 401.
  - `POST /admin/api/set-password` `{"token":..,"password":..}` → 204 (no auto login); 400 `{"error":"weak_password"}`; 410 `{"error":"invalid_token"}`.
  - `LegacyAdminRoutes`: Vert.x routes for `/wp-admin`, `/wp-admin/*`, `/wp-login.php` → 302 `Location: /admin/`.
  - CLI `stillpress user reset-password <login>` → prints `<home_url>/admin/set-password?token=…` (path only when `home_url` unset); exit 1 + message on unknown login.

- [ ] **Step 1: Write failing tests**

```java
class LoginLimiterTest {
  MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
  LoginLimiter l = new LoginLimiter(clock);
  @Test void fiveFailuresBlockTheAccountForTheRestOfTheWindow() {
    for (int i = 0; i < 5; i++) l.failure("Admin", "10.0.0." + i);
    assertThat(l.blocked("admin", "10.9.9.9")).isPresent();
    clock.advance(Duration.ofMinutes(15).plusSeconds(1));
    assertThat(l.blocked("admin", "10.9.9.9")).isEmpty();
  }
  @Test void twentyFailuresAcrossAccountsBlockTheIp() {
    for (int i = 0; i < 20; i++) l.failure("user" + i, "10.0.0.1");
    assertThat(l.blocked("someone-else", "10.0.0.1")).isPresent();
    assertThat(l.blocked("someone-else", "10.0.0.2")).isEmpty();
  }
}

@QuarkusTest class AuthResourceTest {
  // @BeforeEach: store.reset(); create "admin" (administrator, password "correct horse battery"); reset limiter
  @Test void loginSetsHttpOnlyLaxCookie() {
    login("admin", "correct horse battery").then().statusCode(204)
      .cookie("sp_session", detailedCookie().httpOnly(true).sameSite("Lax").path("/"));
  }
  @Test void loginByEmailWorks() { login("a@example.org", "correct horse battery").then().statusCode(204); }
  @Test void unknownAndWrongGiveSameAnswer() {
    var a = login("nobody", "correct horse battery").then().statusCode(401).extract().asString();
    var b = login("admin", "wrong horse battery!").then().statusCode(401).extract().asString();
    assertThat(a).isEqualTo(b);
  }
  @Test void sixthAttemptIsRateLimitedEvenWithRightPassword() {
    for (int i = 0; i < 5; i++) login("admin", "wrong horse battery!");
    login("admin", "correct horse battery").then().statusCode(429).header("Retry-After", notNullValue());
  }
  @Test void importedUserWithoutPasswordCannotLogIn() { /* user created with no hash → 401 */ }
  @Test void secureCookieBehindHttpsProxy() {
    given().header("X-Forwarded-Proto", "https").header("X-Requested-With", "stillpress").contentType(JSON)
      .body(Map.of("login", "admin", "password", "correct horse battery")).post("/admin/api/login")
      .then().cookie("sp_session", detailedCookie().secured(true));
  }
  @Test void logoutKillsTheSession() { /* login, logout, GET /admin/api/session with old cookie → 401 */ }
  @Test void setPasswordTokenIsSingleUse() {
    var raw = tokens.create(adminId, "set_password", Duration.ofHours(24));
    setPassword(raw, "a brand new password").then().statusCode(204);
    setPassword(raw, "another new password").then().statusCode(410);
    login("admin", "a brand new password").then().statusCode(204);
  }
  @Test void wpAdminRedirects() {
    given().redirects().follow(false).get("/wp-login.php").then().statusCode(302).header("Location", "/admin/");
    given().redirects().follow(false).get("/wp-admin/post.php").then().statusCode(302).header("Location", "/admin/");
  }
}

@QuarkusTest class UserCommandTest {
  @Test void printsSetPasswordLinkUnderHomeUrl() { /* settings.put("home_url","https://blog.example.ru"); run via CommandLine with injected factory; output starts with "https://blog.example.ru/admin/set-password?token=" */ }
  @Test void unknownLoginExits1() { }
}
```

`MutableClock` is a small test helper (`extends Clock`, `advance(Duration)`) in `src/test/java/.../account/`.

- [ ] **Step 2: Run tests, verify fail**

Run: `mise exec -- mvn -q test -Dtest='LoginLimiterTest,AuthResourceTest,UserCommandTest'`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement the types above**

- [ ] **Step 4: Run tests, verify pass** — same command → PASS

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: password login with rate limiting, set-password tokens, CLI reset"
git push
```

---

### Task 3: Admin front-end shell (Quinoa + Bun) and the setup wizard

**Files:**
- Create: `src/main/webui/package.json`, `bun.lock`, `vite.config.js`, `index.html`, `src/main.jsx`, `src/api.js`, `src/theme.css`, `src/pages/Setup.jsx`, `src/pages/Login.jsx`, `src/pages/SetPassword.jsx`, `src/pages/Dashboard.jsx`, `admin/SetupToken.java`, `admin/SetupResource.java`, `admin/SiteResource.java`
- Modify: `mise.toml` (`bun = "1.4.4"`), `pom.xml` (`io.quarkiverse.quinoa:quarkus-quinoa:2.9.3`), `application.properties`, `.gitignore` (`src/main/webui/node_modules/`, `src/main/webui/dist/`, `.quinoa/`)
- Test: `admin/SetupResourceTest.java`, `admin/AdminShellTest.java`, `BunVersionPinTest.java`

**Interfaces:**
- Consumes: Tasks 1–2; `SettingRepository`, `SitePublisher.publishAll()`, `ItemRepository.count()`.
- Produces:
  - `application.properties`: `quarkus.quinoa.ui-root-path=/admin`, `quarkus.quinoa.build-dir=dist`, `quarkus.quinoa.enable-spa-routing=true`, `quarkus.quinoa.package-manager-install=true`, `quarkus.quinoa.package-manager-install.bun-version=1.4.4`, `quarkus.quinoa.ignored-path-prefixes=/api` (relative to the UI root; `AdminShellTest` pins the effective behaviour — adjust to `/admin/api` if Quinoa reads it absolutely).
  - `vite.config.js`: `base: '/admin/'`, `@vitejs/plugin-react`.
  - `package.json` exact pins: `react` 19.3.1, `react-dom` 19.3.1, `react-router` 8.5.1; dev: `vite` 8.3.5, `@vitejs/plugin-react` 6.2.1; scripts `start: vite`, `build: vite build`.
  - `src/api.js`: `api(path, {method, body})` → `fetch('/admin/api' + path, …)` with `X-Requested-With: stillpress`, JSON body, `credentials: 'same-origin'`; throws `{status, error}` on non-2xx. Every page uses it.
  - Routes (react-router, basename `/admin`): `/setup`, `/login`, `/set-password`, `/` (dashboard), `/users` (Task 4), `/import` (Task 5). On load: `GET /setup` says `required` → go to `/setup`; else `GET /session` 401 → `/login?next=…`.
  - `@ApplicationScoped class SetupToken { void onStart(@Observes StartupEvent e); Optional<String> current(); boolean matches(String candidate); void discard(); }` — rule in Global Constraints; HTTP-enabled check reads `quarkus.http.host-enabled` (default true).
  - `GET /admin/api/setup` (public) → `{"required": true|false}`; required = `!users.anyAdministratorWithPassword()`.
  - `POST /admin/api/setup` (public) `{"token","title","language","login","email","displayName","password"}` → 201 + session cookie; 403 `{"error":"bad_token"}`; 409 `{"error":"already_set_up"}`; 400 `{"error":"invalid", "field": "<name>"}`. In one `@Transactional` method, serialised by a `synchronized`/`ReentrantLock` section so two concurrent calls cannot both see "not set up": writes settings `title`, `language` (`^[a-z]{2,3}(-[A-Z]{2})?$`), `home_url` = request scheme + host (forwarded), defaults `posts_per_page`=10, `posts_per_rss`=10, `category_base`=`category`, `tag_base`=`tag` (only when absent), creates the administrator (or upgrades an existing row with that login to administrator), sets its password, discards the token. After commit it calls `SitePublisher.publishAll()` so `/` serves the empty site.
  - `GET /admin/api/site` (any logged-in role) → `{"title","homeUrl","language","items","comments"}`.
  - Wizard UI: step 1 token + site title + language + administrator fields; step 2 two cards, **Start empty** (→ dashboard) and **Import from WordPress** (→ `/import`, Task 5). Design: invoke the **ui-ux-pro-max** skill once for the admin shell (product type "CMS admin", minimal, accessible), record its palette/typography/spacing as CSS custom properties in `theme.css`, and use them in every page; forms have labels, visible focus, error text tied with `aria-describedby`.

- [ ] **Step 1: Write failing tests**

```java
class BunVersionPinTest {
  @Test void quinoaBunEqualsMise() throws IOException {
    var mise = Files.readString(Path.of("mise.toml"));
    var props = Files.readString(Path.of("src/main/resources/application.properties"));
    var m = Pattern.compile("(?m)^bun\\s*=\\s*\"([^\"]+)\"").matcher(mise);
    var p = Pattern.compile("(?m)^quarkus\\.quinoa\\.package-manager-install\\.bun-version=(\\S+)").matcher(props);
    assertThat(m.find() && p.find()).isTrue();
    assertThat(p.group(1)).isEqualTo(m.group(1));
  }
}

@QuarkusTest class SetupResourceTest {
  @Inject SetupToken token; // @BeforeEach store.reset() and force a fresh token via a package-private reset hook
  @Test void requiredUntilAdministratorExists() {
    given().get("/admin/api/setup").then().body("required", is(true));
    setup(token.current().orElseThrow()).then().statusCode(201).cookie("sp_session");
    given().get("/admin/api/setup").then().body("required", is(false));
  }
  @Test void wrongTokenIs403() { setup("not-the-token").then().statusCode(403); }
  @Test void secondSetupIs409EvenWithTheToken() {
    var t = token.current().orElseThrow();
    setup(t).then().statusCode(201);
    setup(t).then().statusCode(409);
  }
  @Test void concurrentSetupsCreateOneAdministrator() throws Exception {
    var t = token.current().orElseThrow();
    var codes = IntStream.range(0, 8).parallel().mapToObj(i -> setup(t, "admin" + i).statusCode()).toList();
    assertThat(codes).containsOnlyOnce(201);
    assertThat(users.countAdministrators()).isEqualTo(1);
  }
  @Test void setupPublishesAnEmptySite() {
    setup(token.current().orElseThrow()).then().statusCode(201);
    assertThat(dataDir.current().resolve("index.html")).exists();
  }
  @Test void badLanguageIs400() { /* language "Russian" → 400, field "language" */ }
}

@QuarkusTest class AdminShellTest {
  @Test void spaServedForClientRoutes() {
    given().get("/admin/setup").then().statusCode(200).contentType(startsWith("text/html")).body(containsString("<div id=\"root\""));
  }
  @Test void apiIsNotSwallowedBySpaRouting() {
    given().get("/admin/api/setup").then().contentType(startsWith("application/json"));
  }
  @Test void siteRouteStillServesRoot() { /* after setup, GET / → 200 html from output/current */ }
}
```

- [ ] **Step 2: Run tests, verify fail**

Run: `mise exec -- mvn -q test -Dtest='BunVersionPinTest,SetupResourceTest,AdminShellTest'`
Expected: FAIL — classes missing, no `bun` in `mise.toml`.

- [ ] **Step 3: Scaffold the web UI and install**

Run: `cd src/main/webui && mise exec -- bun install` → creates `bun.lock`.

- [ ] **Step 4: Implement the Java types and the four pages above**

- [ ] **Step 5: Run tests, verify pass** — same command → PASS

- [ ] **Step 6: Check it in a browser**

Start dev mode with the `quarkus-agent` MCP, read the setup token from its log, open `http://localhost:8080/admin/` with the Playwright MCP: wizard → Start empty → dashboard shows the title; `/` shows the empty site. Take one screenshot of the wizard for the commit message context.

- [ ] **Step 7: Commit**

```bash
git add mise.toml pom.xml .gitignore src
git commit -m "feat: admin shell with token-protected setup wizard"
git push
```

---

### Task 4: Users screen

**Files:**
- Create: `admin/UsersResource.java`, `src/main/webui/src/pages/Users.jsx`
- Test: `admin/UsersResourceTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `TokenStore`, `Role`, `SettingRepository` (`home_url`).
- Produces (all `@RolesAllowed("administrator")`):
  - `GET /admin/api/users` → `[{"id","login","displayName","email","role","hasPassword"}]` ordered by login.
  - `POST /admin/api/users` `{"login","email","displayName","role"}` → 201 `{"id", "setPasswordUrl"}`; 409 `{"error":"login_taken"}`; 400 unknown role.
  - `PUT /admin/api/users/{id}/role` `{"role"}` → 204; 409 `{"error":"last_administrator"}` when it would leave zero administrators with a password.
  - `POST /admin/api/users/{id}/password-link` → 200 `{"url"}` (same link form as the CLI; the administrator hands it over until SMTP exists in plan 02c).
  - `Users.jsx`: table, "Add user" form, role select per row, "Copy set-password link" button; shown in the shell nav only for administrators.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class UsersResourceTest {
  // @BeforeEach: reset; admin (administrator, password) + session cookie `adminCookie`; writer (author, password) + `writerCookie`
  @Test void administratorListsUsers() {
    asAdmin().get("/admin/api/users").then().statusCode(200).body("login", contains("admin", "writer"));
  }
  @Test void nonAdministratorIs403() { asWriter().get("/admin/api/users").then().statusCode(403); }
  @Test void createReturnsWorkingSetPasswordLink() {
    var url = asAdmin().body(Map.of("login", "editor1", "email", "e@example.org", "displayName", "E", "role", "editor"))
      .post("/admin/api/users").then().statusCode(201).extract().<String>path("setPasswordUrl");
    var token = url.substring(url.indexOf("token=") + 6);
    setPassword(token, "editor password 1").then().statusCode(204);
    login("editor1", "editor password 1").then().statusCode(204);
  }
  @Test void duplicateLoginIs409() { /* create "writer" again → 409 login_taken */ }
  @Test void lastAdministratorCannotBeDemoted() {
    asAdmin().body(Map.of("role", "editor")).put("/admin/api/users/" + adminId + "/role").then().statusCode(409);
  }
  @Test void demotionAllowedWhenAnotherAdministratorExists() { /* promote writer first → then demote admin → 204 */ }
}
```

- [ ] **Step 2: Run, verify fail** — `mise exec -- mvn -q test -Dtest=UsersResourceTest` → FAIL.
- [ ] **Step 3: Implement** the endpoints and `Users.jsx`.
- [ ] **Step 4: Run, verify pass** → PASS.
- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: users screen with roles and set-password links"
git push
```

---

### Task 5: Import from the wizard: upload, background job, at-most-once guard

**Files:**
- Create: `importer/ImportGuard.java`, `importer/ImportListener.java`, `importer/ImportJob.java`, `admin/ImportResource.java`, `src/main/webui/src/pages/Import.jsx`
- Modify: `importer/WordPressImporter.java` (listener parameter; merge authors by login), `importer/ImportCommand.java` (use `ImportGuard`), `store/SettingRepository.java` (add `void delete(String key)`), `TestStore.java` (`reset()` already deletes settings, which covers `import_state`)
- Test: `importer/ImportGuardTest.java`, `admin/ImportResourceTest.java`, additions to `importer/WordPressImporterTest.java`

**Interfaces:**
- Consumes: `WordPressImporter`, `ImportOptions`, `SitePublisher.publishAll()`, `DataDir`, `SettingRepository`.
- Produces:
  - `interface ImportListener { default void item(int done) {} default void media(int done) {} ImportListener NONE = new ImportListener() {}; }`; new overload `WordPressImporter.importWxr(InputStream in, int mediaLimit, ImportOptions options, ImportListener listener)`; the existing overloads pass `NONE`. Called after each stored item and each downloaded media file.
  - Author merge: when a WXR author's login already exists (the wizard's administrator), reuse that row: keep its `role` and `password_hash`, fill `display_name`/`email` only where null, set `source_id`; existing slugs seed the run's slug set so new authors never collide.
  - `@ApplicationScoped class ImportGuard { static final String STATE = "import_state"; void begin() throws AlreadyImportedException /* atomically: check rule from Global Constraints, then put STATE=running; synchronized */; void done(); void failed(String message); Optional<String> state(); }` — `AlreadyImportedException` gains a constructor taking a message.
  - `ImportCommand` calls `guard.begin()` before and `guard.done()`/`guard.failed(..)` after `importWxr`; exit 1 on `AlreadyImportedException` as before.
  - `@ApplicationScoped class ImportJob { void start(Path wxr, ImportOptions options) throws AlreadyImportedException /* guard.begin(), then a virtual thread: import → publishAll → guard.done(); on exception guard.failed(msg) */; Status status(); record Status(String state /*idle|running|done|failed*/, int items, int media, String report, String error) {} }` — the uploaded file is moved to `<data>/import/<epoch-millis>.xml` before the thread starts and deleted when it ends.
  - `POST /admin/api/import` (`@RolesAllowed("administrator")`, multipart: `file`, `postsPerPage`, `postsPerRss`, `categoryBase`, `tagBase`) → 202 `Status`; 409 `{"error":"already_imported","message"}`; 400 on invalid `ImportOptions` with its message.
  - `GET /admin/api/import` (administrator) → `Status`.
  - `Import.jsx`: file input, the four options (defaults 10/10/category/tag, with a note that WordPress's Settings → Reading and Permalinks show the real values), progress polled every second, the report text when done, the failure message with "wipe the data directory and run setup again" when it failed after writing content. Note shown before upload: the import replaces the site title and language with the export's.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class ImportGuardTest {
  @Test void secondBeginFails() throws Exception {
    guard.begin();
    assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
  }
  @Test void retryAllowedAfterFailureThatLeftNothing() throws Exception {
    guard.begin(); guard.failed("bad xml");
    guard.begin(); // no throw
  }
  @Test void refusedWhenContentExistsEvenWithoutState() throws Exception {
    store.importSample(); settings.delete(ImportGuard.STATE); // SettingRepository gains delete(String)
    assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
  }
}

// WordPressImporterTest additions
@Test void existingAdministratorIsMergedByLogin() throws Exception {
  var admin = users.create("mama", "m@example.org", "Mama", Role.ADMINISTRATOR);
  users.setPassword(admin.getId(), Passwords.hash("correct horse battery"));
  store.importSample();
  var mama = users.findByLoginOrEmail("mama").orElseThrow();
  assertThat(mama.getId()).isEqualTo(admin.getId());
  assertThat(mama.getRole()).isEqualTo("administrator");
  assertThat(mama.getSlug()).isEqualTo("mama");
  assertThat(users.findByLoginOrEmail("papa").orElseThrow().getRole()).isEqualTo("author");
}
@Test void listenerSeesEveryItem() { /* counting listener → final item count == report.items() */ }

@QuarkusTest class ImportResourceTest {
  // @BeforeEach: reset, admin + cookie
  @Test void uploadImportsAndPublishesInBackground() {
    upload(sampleBytes()).then().statusCode(202);
    await().atMost(Duration.ofSeconds(30)).until(() -> status().equals("done")); // poll GET /admin/api/import
    assertThat(dataDir.current().resolve("2023/01/14/месяц-ноябрь-год-2022/index.html")).exists();
  }
  @Test void secondUploadIs409() { /* after done → 409 already_imported */ }
  @Test void uploadLargerThanDefaultBodyLimitIsAccepted() {
    // sample WXR with an 11 MB XML comment appended before </rss> → 202
  }
  @Test void malformedFileFailsAndAllowsRetry() {
    upload("<rss><not-closed>".getBytes(UTF_8)).then().statusCode(202);
    await().until(() -> status().equals("failed"));
    upload(sampleBytes()).then().statusCode(202);
  }
  @Test void nonAdministratorIs403() { }
}
```

The polling helper uses a plain loop with `Thread.sleep(100)` and a deadline unless Awaitility is already on the test classpath (it is not in plan 01; do not add it for two tests).

- [ ] **Step 2: Run, verify fail**

Run: `mise exec -- mvn -q test -Dtest='ImportGuardTest,WordPressImporterTest,ImportResourceTest,ImportCommandTest'`
Expected: FAIL.

- [ ] **Step 3: Implement** the types above.
- [ ] **Step 4: Run, verify pass** → PASS; then the full suite `mise exec -- mvn -q verify` → PASS (the importer change touches plan 01 behaviour).
- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: wizard WXR import as a background job, import at most once"
git push
```

---

### Task 6: Serving Mode 2: generated nginx include

**Files:**
- Create: `serve/NginxConfig.java`, `serve/NginxConfigCommand.java`, `src/main/resources/templates/nginx/stillpress.conf`, `docs/deploy/nginx-site.conf.example`
- Modify: `StillpressCommand.java` (register `NginxConfigCommand`), `pom.xml` (test dep `org.testcontainers:testcontainers:${testcontainers.version}`), `README.md` ("Serving in production": Mode 2 is now real; how to generate and include the file; `STILLPRESS_TRUSTED_PROXIES`)
- Test: `serve/NginxConfigTest.java`, `serve/NginxModeTwoTest.java` (surefire runs it; it skips via `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable())` when Docker is absent)

**Interfaces:**
- Consumes: `DataDir.current()`, `SitePublisher.FEED_SEGMENT`, `StaticSiteRoute.SHORT_CACHE`/`LONG_CACHE`.
- Produces:
  - `final class NginxConfig { static final List<String> DYNAMIC_PREFIXES = List.of("/admin", "/wp-admin", "/wp-login.php"); static String render(Path root, String upstream) }` — later plans append prefixes (`/wp-json` in 02b, comment submission and `/podcast-download/` in 04/05).
  - CLI `stillpress nginx-config [--root <path>] [--upstream <host:port>]`; defaults: root = `DataDir.current()` absolute, upstream `127.0.0.1:8080`. Prints the include to stdout.
  - The include is `server`-level (goes inside the operator's `server {}` that already has `listen`, `server_name`, TLS). Required behaviour, in this order:
    - `root <root>;` `index index.html;` `charset utf-8;`
    - fallback: a named location `@stillpress` with `proxy_pass http://<upstream>;` and headers `Host $host`, `X-Forwarded-For $proxy_add_x_forwarded_for`, `X-Forwarded-Proto $scheme`, `X-Forwarded-Host $host`; `client_max_body_size 1g;`.
    - each `DYNAMIC_PREFIXES` entry: `location ^~ <prefix> { proxy_pass …; same headers; client_max_body_size 1g; }` (`= /wp-login.php` for the file).
    - shortlinks: in `location /`, `error_page 418 = @stillpress; if ($args ~* "(^|&)(p|page_id|attachment_id)=") { return 418; }` then `try_files $uri $uri/index.html @stillpress;` — `$uri/` is not used, so a directory without trailing slash falls through to Stillpress, which issues WordPress's 301.
    - feeds: `location ~ "/feed/$" { default_type "application/rss+xml; charset=UTF-8"; types {} ; try_files $uri/index.html @stillpress; add_header Cache-Control "<SHORT_CACHE>"; }`
    - `location ^~ /media/` and `location ^~ /assets/`: `try_files $uri @stillpress; add_header Cache-Control "<LONG_CACHE>";`
    - HTML: `add_header Cache-Control "<SHORT_CACHE>";` in `location /`.
  - `docs/deploy/nginx-site.conf.example`: a full `server {}` with `listen 80`, `server_name example.org`, and `include /etc/nginx/stillpress.conf;`, plus a two-line note that TLS lines go here and that nginx needs read access to the data directory's `output/` (the `current` symlink is relative, so mounting `output/` anywhere works).

- [ ] **Step 1: Write failing tests**

```java
class NginxConfigTest {
  @Test void includesRootFallbackAndDynamicPrefixes() {
    var c = NginxConfig.render(Path.of("/srv/sp/output/current"), "127.0.0.1:8080");
    assertThat(c).contains("root /srv/sp/output/current;")
      .contains("try_files $uri $uri/index.html @stillpress;")
      .contains("location ^~ /admin").contains("location = /wp-login.php")
      .contains("application/rss+xml").doesNotContain("$uri/ ");
  }
}

// Testcontainers nginx:1.29-alpine; mounts the test release's output/ read-only at /site/output and the rendered
// include (root /site/output/current, upstream host.testcontainers.internal:<quarkus test port>) as /etc/nginx/conf.d/default.conf
// wrapped in `server { listen 80; include …; }`. Testcontainers.exposeHostPorts(testPort). @BeforeEach imports + publishes the sample.
@QuarkusTest class NginxModeTwoTest {
  @Test void servesCyrillicPathsInBothHexCases() {
    get("/2023/01/14/%D0%BC%D0%B5%D1%81%D1%8F%D1%86-%D0%BD%D0%BE%D1%8F%D0%B1%D1%80%D1%8C-%D0%B3%D0%BE%D0%B4-2022/").then().statusCode(200);
    get("/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/").then().statusCode(200);
  }
  @Test void feedIsRss() { get("/feed/").then().statusCode(200).contentType(startsWith("application/rss+xml")); }
  @Test void unknownPathGetsStillpress404() { get("/nope/").then().statusCode(404).body(containsString("<html")); }
  @Test void shortlinkReachesRedirectMap() {
    get("/?p=5994").then().statusCode(301).header("Location", containsString("/2023/01/14/"));
  }
  @Test void missingSlashRedirectsLikeWordPress() { get("/about").then().statusCode(301).header("Location", endsWith("/about/")); }
  @Test void adminIsProxied() { get("/admin/api/setup").then().statusCode(200).contentType(startsWith("application/json")); }
  @Test void publishSwapIsPickedUpWithoutReload() { /* publishAll() again → GET / still 200, served from the new release */ }
}
```

`get(path)` = RestAssured against the nginx container's mapped port with `urlEncodingEnabled(false)` and redirects not followed.

- [ ] **Step 2: Run, verify fail**

Run: `mise exec -- mvn -q test -Dtest='NginxConfigTest,NginxModeTwoTest'`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement** `NginxConfig` (Qute template `nginx/stillpress.conf`), the command, the example, the README section.
- [ ] **Step 4: Run, verify pass** → PASS (Docker running).
- [ ] **Step 5: Commit**

```bash
git add pom.xml README.md docs/deploy src
git commit -m "feat: generated nginx include for serving Mode 2"
git push
```

---

### Task 7: Acceptance on the real blog through nginx (manual)

**Files:** results note `docs/superpowers/plans/2026-11-04-stillpress-02a-results.md`; `ROADMAP.md` updated (02a done, carried-over items).

- [ ] **Step 1: Fresh install** — `rm -rf data-02a`; `STILLPRESS_DATA_DIR=./data-02a mise exec -- mvn -q package -DskipTests && STILLPRESS_DATA_DIR=./data-02a java -jar target/quarkus-app/quarkus-run.jar serve`; copy the setup token from the log.
- [ ] **Step 2: nginx** — `java -jar … nginx-config --root /site/output/current --upstream host.docker.internal:8080 > data-02a/stillpress.conf`; run `docker run --rm -p 18083:80 --add-host host.docker.internal:host-gateway -v $PWD/data-02a/output:/site/output:ro -v $PWD/docs/deploy/nginx-site.conf.example:/etc/nginx/conf.d/default.conf:ro -v $PWD/data-02a/stillpress.conf:/etc/nginx/stillpress.conf:ro nginx:1.29-alpine`.
- [ ] **Step 3: Wizard through nginx** — Playwright MCP at `http://localhost:18083/wp-admin` → lands on `/admin/setup`; create the administrator with login `asm0dey`; choose Import; upload `data-blog/import/blog.xml` with posts per page 30, posts per RSS 50; watch progress to done. Expected: report matches plan 01's (3,679 items, 15 comments); the `asm0dey` author was merged, not duplicated.
- [ ] **Step 4: Second import refused** — the Import screen and `java -jar … import --wxr …` both refuse.
- [ ] **Step 5: Users** — an imported author has no password; create a set-password link, set it in a private window, log in as that author; the Users nav item is hidden for them and `/admin/api/users` is 403. Six wrong passwords in a row → the 6th shows the rate-limit message.
- [ ] **Step 6: URL parity through nginx** — `java -jar … verify-urls --source https://blog.asm0dey.ru --target http://localhost:18083`. Expected: the same pass set as plan 01 (830 pass; mismatches only in the causes listed in `2026-11-03-stillpress-01-results.md`).
- [ ] **Step 7: Write the results note and update `ROADMAP.md`** (mark 02a done; list anything deferred to 02b/02c), commit, push.

---

## Self-review notes

- Roadmap coverage for 02a: Mode 2 include + example + acceptance through nginx (T6, T7); wizard with one-time token, Start empty, WXR upload (T3, T5); admin at `/admin`, `/wp-admin` and `/wp-login.php` redirects (T2); Quinoa + pinned Bun with build check, ui-ux-pro-max (T3); password login with Argon2id and per-account/per-IP limits (T1, T2); multiple users with the four roles, imported authors become accounts without passwords (T1, T4, T5); server CLI password reset without SMTP (T2); server-side "import at most once", including the carried-over "check more than items" item (T5).
- Left for 02b: editor, WP REST slice (`/wp-json` joins `DYNAMIC_PREFIXES` there), statuses and scheduling, incremental and background republish, preview, media library, reusable-block editor, revisions, admin i18n, role capabilities beyond "administrator manages users and import".
- Left for 02c: TOTP, passkeys, mandatory 2FA, CAPTCHA (`none|turnstile|altcha`) on login/reset/setup, SMTP settings, email reset and invites (they reuse `TokenStore` and the set-password page).
