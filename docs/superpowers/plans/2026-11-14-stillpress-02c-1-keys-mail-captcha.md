# 02c-1: keys, mail, invites and reset, CAPTCHA, hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Imported authors and new users get an invite email and set a password. Anyone resets a forgotten password by email. Login, reset, set-password and setup are protected by a `none | turnstile | altcha` CAPTCHA and a honeypot. Stored secrets are encrypted with Tink. The 02c hardening items from 02a and 02b are closed.

**Architecture:**
- **Keys.** The `Keys` loader owns two Tink keysets (AEAD and PRF) in `<data-dir>/keys/`, or in an override directory. It is used by a `ConfigSourceFactory`, which supplies the CSRF signing key, and by the CDI `Secrets` bean, which stores encrypted values in a new `secret` table.
- **Mail.** `SiteMail` builds a Vert.x `MailClient` from SMTP settings stored in the database. Mail is sent after the transaction commits, through a CDI transactional observer. Mail texts are Qute message bundles in English and Russian.
- **Links.** Every emailed or printed link is built from a new `admin_url` setting, never from an anonymous request's host.
- **CAPTCHA.** calit's verifier and ALTCHA endpoint are ported into a `Captcha` bean, and every public form checks it.

**Tech Stack:** existing stack (Java 25, Quarkus 3.40, jOOQ, Flyway, H2/PostgreSQL, Qute, Vert.x, React + TypeScript + Bun). New: `com.google.crypto.tink:tink:1.24.0`, `io.quarkus:quarkus-mailer` (platform BOM), `org.altcha:altcha:2.1.1`, npm `altcha@3.6.0` (exact pin), and the test-only `com.icegreen:greenmail-junit5:2.1.15`.

**Spec:** `docs/superpowers/specs/2026-11-14-stillpress-02c-account-security-design.md` (the 02c design: authority for every decision here), `docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md` §6.2.2, `docs/superpowers/plans/ROADMAP.md` ("02c" bullets under "Carried over from 02a" and "Carried over from 02b").

## Global Constraints

- Everything in the Global Constraints of plans 01, 02a, 02b and the 02b follow-up still holds:
  - package root `site.asm0dey.stillpress`;
  - mise;
  - portable Flyway SQL in `src/main/resources/db/migration/common`. The same file runs on H2 and PostgreSQL, so no dialect-only syntax;
  - jOOQ codegen via `mise exec -- mvn -q -Pcodegen generate-sources` after every migration;
  - the admin UI is TypeScript and `tsc --noEmit` passes;
  - every new admin string is wrapped in `__('…', 'stillpress')`, with a Russian entry in `src/main/webui/src/i18n/ru.json` (`strings.test.ts` enforces this).
- Work on `main`; push every commit to `origin`.
- Binding precedents (copy into every implementer and reviewer prompt):
  - AssertJ only, asserting on values (`containsExactly`, `hasSize`, `isEqualTo`). Never `assertThat(boolean).isTrue()` for something that has a value. Hamcrest only inside RestAssured `.body()`.
  - Set-based SQL: one statement for bulk work, no per-row loops.
  - No production interface or wrapper that exists only for tests.
  - Framework capabilities before own code.
  - Trust jOOQ: no SQL probes.
  - Read jOOQ results by field, never by position.
  - JDK imports are `import module java.base;`.
  - Tink keyset, never a raw key or a hand-rolled AES helper (precedent `encryption-tink`).
  - Quarkus docs via `quarkus_searchDocs`.
  - Tests via the `quarkus-agent` dev-mode tools (`devui-testing_runTest {"className":"..."}`). Maven only for spotless, codegen, package and the final `verify`.
  - After a `pom.xml` change: `quarkus_stop` + `quarkus_start`.
- **No link from a request host.** An emailed or printed link is built only from the `admin_url` setting, and is path-only when it is unset. Setup and the first administrator `/session` call after upgrade fill `admin_url` from the request; nothing else writes it except the Settings screen.
- **Mail never fails the action.** A send failure is logged at WARN and stored in the `mail_last_error` setting. The HTTP response of the action that triggered it is unchanged.
- **Enumeration-safe reset.** `POST /admin/api/password-reset` answers 204 for every syntactically valid request, whether or not the account exists, has an email, or a mail is sent. The only exceptions are 429 (IP rate limit) and 400 `captcha_failed`.
- **CAPTCHA off means off.** With provider `none`, no request needs a token. The honeypot field `website` is checked under every provider.
- Constants pinned by this plan:
  - token TTLs: invite 7 days, email reset 1 hour, CLI reset and admin "Copy link" 24 hours;
  - one reset mail per account per 5 minutes;
  - ALTCHA challenge expiry 300 s, `maxNumber` 100000;
  - keyset files `aead.json` (template `AES256_GCM`) and `prf.json` (template `HMAC_SHA256_PRF`).

## Review Focus

1. **Reset-link poisoning.** An anonymous reset request with a forged `Host` or `X-Forwarded-Host` must still mail a link on `admin_url`. Task 7 test `resetLinkIgnoresRequestHost`.
2. **SMTP down while creating a user.** The user is still created (201), `mail_last_error` holds the failure, and no exception reaches the client. Task 7 test `inviteMailFailureKeepsTheUser`.
3. **Keyset lost while secrets exist.** `serve` refuses to start and removes the keyset it just generated, so a restored keyset is not shadowed. An override directory without keyset files fails with a message naming the directory. A value encrypted under another keyset reads as "not set", never a 500. Task 1 tests `serveRefusesFreshKeysWithStoredSecrets`, `overrideDirWithoutKeysFails`, `foreignCiphertextReadsAsAbsent`.
4. **Bad Turnstile secret, or a provider locked by the environment.** Saving a bad secret is refused and the provider stays as it was. With `STILLPRESS_CAPTCHA_PROVIDER` set, the admin PUT answers 409 `captcha_locked`, and the CLI `captcha none` warns on stderr. Task 9 tests `badTurnstileSecretIsRefused`, `envProviderLocksTheSetting`, `cliWarnsWhenEnvOverrides`.
5. **Two concurrent creates with the same email in different case.** One gets 201, the other 409 `email_taken`, never 500. Task 4 test `concurrentCreatesSameEmailDifferentCase`.

---

## File Structure

- `config/Keys.java`, `config/KeysConfigSourceFactory.java`, `META-INF/services/io.smallrye.config.ConfigSourceFactory`, `store/SecretRepository.java`, `account/Secrets.java`, `account/SecretsCommand.java`, V7 migration (Task 1).
- `store/TokenRepository.java`, `admin/AuthResource.java`, `account/LoginLimiter.java` (Task 2).
- `admin/Origins.java`, `admin/SetupResource.java`, `account/SetPasswordLink.java`, `admin/SettingsResource.java`, `webui/src/pages/Settings.tsx` (Task 3).
- `store/AuthorRepository.java`, `importer/WordPressImporter.java`, `admin/UsersResource.java`, `webui/src/pages/{Users,Profile}.tsx` (Task 4).
- `account/BodyLimitFilter.java` (Task 5).
- `mail/SiteMail.java`, `mail/Mail.java`, `mail/MailTexts.java`, `messages/mail_ru.properties`, `admin/MailResource.java`, `webui/src/pages/Security.tsx` (Task 6).
- `admin/UsersResource.java`, `admin/AuthResource.java` (reset), `webui/src/pages/{ForgotPassword,Login,Users}.tsx` (Task 7).
- `mail/ReviewNotifications.java`, `content/ItemService.java` (Task 8).
- `account/Captcha.java`, `account/CaptchaFailedException.java`, `admin/AltchaResource.java`, `admin/CaptchaResource.java`, `account/CaptchaCommand.java` (Task 9).
- `webui/src/Captcha.tsx`, `webui/src/pages/{Login,Setup,SetPassword,ForgotPassword,Security}.tsx` (Task 10).
- `README.md`, `docs/superpowers/plans/ROADMAP.md`, `docs/superpowers/plans/2026-11-14-stillpress-02c-1-results.md` (Task 11).

Paths are relative to `src/main/java/site/asm0dey/stillpress/`, `src/main/resources/` or `src/main/webui/src/` as the extension shows. Tests mirror them under `src/test/java/site/asm0dey/stillpress/` and `src/main/webui/src/`.

---

### Task 1: Tink keys, encrypted secrets, signed CSRF tokens

**Files:**
- Create: `config/Keys.java`, `config/KeysConfigSourceFactory.java`, `src/main/resources/META-INF/services/io.smallrye.config.ConfigSourceFactory`, `store/SecretRepository.java`, `account/Secrets.java`, `account/SecretsCommand.java`, `db/migration/common/V7__secrets_mail.sql`
- Modify: `pom.xml` (Tink), `StillpressCommand.java` (`ServeCommand.run` guard, `SecretsCommand` subcommand), `src/test/java/.../TestStore.java` (clear `secret`)
- Test: `config/KeysTest.java`, `account/SecretsTest.java`, `account/SecretsCommandTest.java`, `admin/CsrfSignatureTest.java`

**Interfaces:**
- Produces: `public final class Keys`:
  - `static Keys get(Path dataDir, Optional<Path> overrideDir)` is memoized per resolved key directory in a static `ConcurrentHashMap<Path, Keys>`. Tests restart Quarkus with different data dirs in one JVM, so a single static instance would be wrong. The first call for a directory loads the keysets or generates them.
  - `KeysetHandle aead()`, `KeysetHandle prf()`;
  - `boolean generatedNow()`: true when this process created the files;
  - `Path dir()`;
  - `void discardGenerated()`: deletes the files when `generatedNow()`.
- Produces: config `stillpress.keys-dir` (env `STILLPRESS_KEYS_DIR`), optional. When set, files are only read there, never written. Missing files throw `IllegalStateException("No Tink keysets in <dir>: expected aead.json and prf.json")`.
- Produces: `@ApplicationScoped Secrets`:
  - `Optional<String> get(String name)`;
  - `void put(String name, String value)`;
  - `void delete(String name)`;
  - `boolean any()`;
  - `byte[] derive(String purpose, int length)`: PRF over the purpose's UTF-8 bytes.
  - AEAD associated data is the name's UTF-8 bytes. `get` returns empty on `GeneralSecurityException` and logs once at WARN.
- Produces: table `secret(name VARCHAR(100) PRIMARY KEY, ciphertext TEXT NOT NULL)`, with base64 ciphertext. In the same V7, `ALTER TABLE user_token ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL` (used in Task 7).
- Produces: CLI `stillpress secrets reset` deletes every `secret` row. 02c-2 extends it to TOTP rows. It prints `Deleted N stored secrets. Re-enter the SMTP password and CAPTCHA secret in the admin.` and exits 0.
- Produces: `quarkus.rest-csrf.token-signature-key` = lowercase hex of `prf.computePrimary("csrf-token-signature".getBytes(UTF_8), 32)`, supplied by `KeysConfigSourceFactory`. It reads `stillpress.data-dir` and `stillpress.keys-dir` from its `ConfigSourceContext`. Ordinal 50: below env and `application.properties`, so a test profile can still set the key.

- [ ] **Step 1: Write `KeysTest` (plain JUnit, temp dirs).**
  - `generatesBothKeysetsOwnerOnly`: on a POSIX file system the files exist and `Files.getPosixFilePermissions` equals `OWNER_READ, OWNER_WRITE`; the directory is `rwx------`; `generatedNow()` is `true`.
  - `secondLoadReadsTheSameKeys`: two separate `Keys` loads (use a package-private non-memoized `load(...)`, which `get` wraps) encrypt and decrypt each other's AEAD output.
  - `overrideDirWithoutKeysFails`: an empty override dir gives `IllegalStateException` with a message containing the dir path, and no files are created in it.
  - `overrideDirIsNeverWritten`: with keys copied into a read-only override dir, the load succeeds and `generatedNow()` is `false`.
- [ ] **Step 2: Run, expect a compile failure.** `devui-testing_runTest {"className":"site.asm0dey.stillpress.config.KeysTest"}`.
- [ ] **Step 3: Implement `Keys`.**
  - Add Tink to `pom.xml`, then `quarkus_stop` + `quarkus_start`.
  - Call `AeadConfig.register()` and `PrfConfig.register()`.
  - Keysets are created from the templates named above and serialized with `TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())`.
  - Write each file to a temp file in the same directory, with POSIX perms set before the content goes in, then `ATOMIC_MOVE`.
  - Look up the primitive API for Tink 1.24 (`getPrimitive(RegistryConfiguration.get(), Aead.class)`) in Tink's javadoc. Don't guess.
- [ ] **Step 4: Run `KeysTest`, expect PASS.**
- [ ] **Step 5: Write `SecretsTest` (`@QuarkusTest`).**
  - `roundTrip`: put `"smtp_password"` → get returns it, and the stored `secret.ciphertext` does not contain the plaintext.
  - `foreignCiphertextReadsAsAbsent`: insert a row encrypted under a freshly generated AEAD keyset, then `get` is `Optional.empty()`.
  - `nameIsBoundToCiphertext`: copy `a`'s ciphertext into row `b`, then `get("b")` is empty.
  - `deriveIsStableAndPurposeBound`: `derive("x",32)` is equal across calls and differs from `derive("y",32)`.
- [ ] **Step 6: Implement the V7 migration, codegen, `SecretRepository`** (`Optional<String> ciphertext(String name)`, `void put(String name, String ciphertext)`, `void delete(String name)`, `int deleteAll()`, `boolean any()`), and `Secrets`. `Secrets` gets its `Keys` from `Keys.get(dataDir.root(), keysDir)`. Add `secret` to `TestStore.clear()`.
- [ ] **Step 7: Run `SecretsTest`, expect PASS.**
- [ ] **Step 8: Write `SecretsCommandTest`.**
  - `resetDeletesStoredSecrets` (pattern of `UserCommandTest`).
  - `serveRefusesFreshKeysWithStoredSecrets`: unit-test the guard method `StillpressCommand.ServeCommand.check(Keys, Secrets)`. It returns exit code 1 and an error text containing `stillpress secrets reset` when `generatedNow() && secrets.any()`, and calls `discardGenerated()`. It returns 0 otherwise.
- [ ] **Step 9: Implement `SecretsCommand` and the guard.** `ServeCommand.run()` calls `check`. On 1 it prints the text to stderr and calls `Quarkus.asyncExit(1)` instead of waiting. The text:

  ```
  Stillpress created a new encryption keyset in <dir>, but the database holds secrets encrypted with another one.
  Restore the previous keyset files (aead.json, prf.json) into <dir>, or run `stillpress secrets reset` to drop the stored secrets.
  ```
- [ ] **Step 10: Write `CsrfSignatureTest`, then implement `KeysConfigSourceFactory`.**
  - `@QuarkusTest`: `ConfigProvider.getConfig().getValue("quarkus.rest-csrf.token-signature-key", String.class)` has 64 hex chars and equals the hex of `secrets.derive("csrf-token-signature", 32)`.
  - Existing CSRF tests (`Csrf.csrf()` helpers) stay green.
  - Check the `ConfigSourceFactory` registration in `quarkus_searchDocs` ("config extending support").
- [ ] **Step 11: Run the account, config and admin test classes, then the full suite. Expect PASS.**
- [ ] **Step 12: Commit.** `feat: Tink keysets, encrypted secrets and signed CSRF tokens`

### Task 2: Token and limiter hardening

**Files:**
- Modify: `store/TokenRepository.java`, `admin/AuthResource.java` (`setPassword`), `account/LoginLimiter.java`
- Test: `admin/AuthResourceTest.java`, `account/LoginLimiterTest.java`

**Interfaces:**
- Produces: `TokenRepository.create(long userId, String purpose, Duration ttl)` first deletes the user's existing tokens of that purpose, in the same transaction as the insert. It also writes `created_at = SessionRepository.now()`.
- Produces: `boolean TokenRepository.issuedSince(long userId, String purpose, Instant since)` (used in Task 7).

- [ ] **Step 1: Write the failing tests.**
  - `AuthResourceTest.newLinkRevokesTheOldOne`: issue two set-password tokens for one user. The first gives 410 `invalid_token`, the second 204.
  - Consume-before-hash only changes timing, so no test pins it. The reviewer checks the order in `setPassword`.
  - `LoginLimiterTest.sweepDropsStaleKeys`: with `MutableClock`, record failures for 1 000 distinct IPs, advance 16 min, make one `blockedIp` call. `limiter.size()` (package-private: total keys in both maps) is 0.
  - `LoginLimiterTest.expiredFailuresStopBlockingWithoutAPrune`: failures older than the window never block, even before a sweep.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
  - `setPassword`: validate the password, then `consume` the token, then hash. All three stay in the method's transaction.
  - `LoginLimiter`: prune only the keys a call touches. Sweep the whole map at most once per `WINDOW` (`lastSweep` field). `wait(...)` counts only failures inside the window.
- [ ] **Step 4: Run both classes, expect PASS. Commit.** `fix: revoke earlier set-password links, check the token before hashing, amortize limiter pruning`

### Task 3: Origins, `admin_url`, and links that never use a request host

**Files:**
- Create: `admin/Origins.java`
- Modify: `admin/SetupResource.java`, `admin/AuthResource.java` (`session`), `account/SetPasswordLink.java`, `account/UserCommand.java`, `admin/SettingsResource.java`, `webui/src/pages/Settings.tsx`, `webui/src/i18n/ru.json`
- Test: `admin/OriginsTest.java`, `admin/SetupResourceTest.java`, `admin/SettingsResourceTest.java`, `account/UserCommandTest.java`

**Interfaces:**
- Produces: `static Optional<String> Origins.of(HttpServerRequest r)` returns `scheme://host[:port]`. It is empty when `r.authority()` is null, and the host is wrapped in `[...]` when it contains `:`.
- Produces: `static boolean Origins.valid(String origin)`: `http` or `https`, a host, an optional port, no path, query or fragment, and IPv6 only in brackets. Use `java.net.URI`.
- Produces: setting `admin_url`.
- Produces: `SetPasswordLink.issue(long userId, Duration ttl)` returns `admin_url + "/admin/set-password?token=…"`, or the path alone when `admin_url` is unset. It never reads `home_url`. Constants: `INVITE_TTL = Duration.ofDays(7)`, `RESET_TTL = Duration.ofHours(1)`, `MANUAL_TTL = Duration.ofHours(24)`.
- Produces: `SettingsResource.Settings` gains `String adminUrl`. It is validated with `Origins.valid` and gives 400 `{"error":"invalid","field":"adminUrl"}`. Saving it never republishes.

- [ ] **Step 1: Write the failing tests.**
  - `OriginsTest`: `ipv6HostIsBracketed` (authority `[::1]:8080` → `http://[::1]:8080`), `noAuthorityIsEmpty`, `validRejectsPathAndBareIpv6` (`https://a.example/x` → false, `http://::1` → false, `http://[::1]:8080` → true).
  - `SetupResourceTest.missingHostIs400`: a raw HTTP/1.0 request without `Host` (a `Socket` writing the request bytes) gives 400 `no_host`, not 500.
  - `SetupResourceTest.setupStoresAdminUrl`: after setup, `admin_url` equals `http://localhost:<port>`, and `home_url` is untouched when it already existed.
  - `AuthResourceTest.firstAdminSessionFillsAdminUrl`: an administrator `GET /session` with no `admin_url` stores the request origin. An editor's call doesn't, and an existing value is never overwritten. Use `SettingRepository.compareAndSet(key, null, value)`.
  - `UserCommandTest.resetLinkUsesAdminUrlOrPath`: with `admin_url` set, the printed line starts with it. Unset, it starts with `/admin/set-password?token=`, and stderr says `admin_url is not set; open the link on the admin's host`.
  - `SettingsResourceTest.adminUrlValidatedAndSaved`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
  - The setup wizard replaces its inline origin code with `Origins.of`, and stores `admin_url` next to `home_url`.
  - The `/session` fill runs only for role administrator.
  - The Settings screen gets an "Admin address" field under "Site address", with the hint `Links in emails point here. Change it when the site moves to its final domain.`
  - All existing `links.issue(id)` call sites pass `MANUAL_TTL` for now. Task 7 switches invites and resets.
- [ ] **Step 4: Run the classes, expect PASS. Commit.** `feat: admin_url for every emailed or printed link; 400 without a Host header`

### Task 4: Case-insensitive unique email

**Files:**
- Create: `db/migration/common/V8__email_lower.sql`
- Modify: `store/AuthorRepository.java`, `importer/WordPressImporter.java:154`, `admin/UsersResource.java`, `admin/SetupResource.java`, `webui/src/pages/{Users,Profile}.tsx`, `ru.json`
- Test: `store/AuthorRepositoryTest.java` (create if absent), `admin/UsersResourceTest.java`, `pg/PostgresAccountsTest.java`

**Interfaces:**
- Produces: column `author.email_lower VARCHAR(100)` with `CREATE UNIQUE INDEX uq_author_email_lower ON author (email_lower)`. It is `email.toLowerCase(Locale.ROOT)`, or NULL when email is null or empty.
- Backfill: one `UPDATE` that sets it only for rows whose lowered email no other row shares. Existing duplicates keep NULL, so the migration never fails on old data.
- `AuthorRepository.create` and `setProfile` set `email_lower`. `emailTaken` and `findByLoginOrEmail` compare `email_lower` against a Java-lowered argument.
- The importer sets `email_lower` unless an earlier author in the same import (or the table) already has it.
- Produces: `static boolean AuthorRepository.isEmailConflict(DataAccessException e)`: sqlState `23505` and the message (lower-cased) contains `uq_author_email_lower`.

- [ ] **Step 1: Write the failing tests.**
  - `UsersResourceTest.concurrentCreatesSameEmailDifferentCase`: two threads POST `A@x.org` and `a@x.org` with different logins. The statuses, sorted, are `[201, 409]`, and the 409 body is `email_taken`. Run on PostgreSQL too: add the same case to `PostgresAccountsTest`, with a 30 s future timeout.
  - `UsersResourceTest.profileUpdateRaceMapsTo409`: the unique violation in `update` gives 409.
  - `AuthorRepositoryTest.backfillSkipsExistingDuplicates`: run the V8 `UPDATE` statement's jOOQ equivalent on two seeded rows sharing an email. Both stay NULL and the other rows are filled.
  - `WordPressImporterTest.duplicateAuthorEmailsImport`: a WXR with two authors whose emails differ only in case imports without error.
- [ ] **Step 2: Run, expect FAIL. Implement. Run codegen.**
- [ ] **Step 3: Fix the misleading UI text.** The error for `field === 'email'` becomes `Enter an email address like name@example.com, at most 100 characters.` (both screens, plus `ru.json`).
- [ ] **Step 4: Run the classes, expect PASS. Commit.** `fix: case-insensitive unique email`

### Task 5: h2c bodies without Content-Length

**Files:** Modify `account/BodyLimitFilter.java`. Test `admin/AdminBodyLimitTest.java`.

- [ ] **Step 1: Write `h2cBodyWithoutLengthIsRefused`.**
  - Use a Vert.x `HttpClient` with `HttpClientOptions().setProtocolVersion(HTTP_2).setHttp2ClearTextUpgrade(false)` (prior knowledge).
  - POST `/admin/api/login` with a 100 KiB body written as two `write`s and no `Content-Length`.
  - Assert status `411`.
  - Add a case where an HTTP/2 GET without a body still gets 200 on `/admin/api/setup`.
- [ ] **Step 2: Run.**
  - If it already passes, the gap isn't real: keep the test, note it in the results, and skip Step 3.
  - If it fails (expected: HTTP/2 has no `Transfer-Encoding`), go to Step 3.
- [ ] **Step 3: Extend the filter's refusal.** On `/admin/api/` paths outside the two upload exclusions, refuse a request with `req.version() == HttpVersion.HTTP_2`, a method other than GET, HEAD or OPTIONS, and no `Content-Length`, with 411.
- [ ] **Step 4: Run `AdminBodyLimitTest`, expect PASS. Commit.** `fix: refuse unbounded h2c bodies on the admin API`

### Task 6: SMTP settings and sending

**Files:**
- Create: `mail/Mail.java`, `mail/SiteMail.java`, `mail/MailTexts.java`, `src/main/resources/messages/mail_ru.properties`, `admin/MailResource.java`, `webui/src/pages/Security.tsx`
- Modify: `pom.xml` (`quarkus-mailer`; test `greenmail-junit5`), `webui/src/main.tsx` (route `/security`), `webui/src/Shell.tsx` (nav link "Security", administrators only), `webui/src/pages/admin.ts` (`mayOpen`), `ru.json`
- Test: `mail/SiteMailTest.java`, `admin/MailResourceTest.java`, `webui/src/pages/security.test.tsx`

**Interfaces:**
- Produces: `record Mail(String to, String subject, String body)`, plain text, UTF-8.
- Produces: `@ApplicationScoped SiteMail`:
  - `boolean configured()`: `smtp_host` is set;
  - `void send(Mail m)` blocks for up to 30 s and throws `MailFailedException(String message)`;
  - `void sendAfterCommit(Mail m)` fires a CDI event. An `@Observes(during = TransactionPhase.AFTER_SUCCESS)` observer calls `send`. On failure it logs at WARN and writes `mail_last_error` (`<ISO instant> <message>`) in `QuarkusTransaction.requiringNew()`. Called outside a transaction, it sends immediately with the same failure handling;
  - `void reload()` closes and rebuilds the `MailClient`.
  - The `MailClient` is created with `MailClient.create(vertx, MailConfig)` from settings `smtp_host`, `smtp_port`, `smtp_security` (`none|starttls|tls`), `smtp_username`, `smtp_from`, and secret `smtp_password`. Check `quarkus_searchDocs` "Using the underlying Vert.x Mail Client".
- Produces: Qute `@MessageBundle("mail") interface MailTexts`, with English defaults in `@Message` and Russian in `messages/mail_ru.properties`:
  - `inviteSubject(String site)`, `inviteBody(String name, String site, String link, int days)`;
  - `resetSubject(String site)`, `resetBody(String name, String site, String link, int minutes)`;
  - `reviewSubject(String site, String title)`, `reviewBody(String author, String title, String link)`;
  - `testSubject(String site)`, `testBody(String site)`.
  - The recipient's locale is `author.locale`, else the site language mapped to `ru|en` as in `AuthResource.session`. Extract that mapping into `static String AuthResource.adminLocale(String userLocale, Optional<String> siteLanguage)` and reuse it.
- Produces: admin-only `MailResource` at `/admin/api/mail`:
  - `GET` → `{host, port, security, username, from, passwordSet, lastError}`;
  - `PUT` the same fields plus `password`. `null` keeps the stored password and `""` deletes it. `host: ""` clears every `smtp_*` setting and the password. Validation gives 400 `{error:"invalid", field}`: port 1–65535, `from` passes `UserFields.validEmail`. Save, then `reload()`;
  - `POST /test` sends `MailTexts.test*` to the signed-in admin's email. It returns 204, 400 `no_email` when the admin has no email, 409 `mail_not_configured`, or 502 `{error:"mail_failed", message}`. A success clears `mail_last_error`.
- Produces: the Security screen's "Email" section: host, port, security select, username, password (placeholder `Saved` when `passwordSet`), from, Save, "Send test email", and `lastError` shown in an alert.

- [ ] **Step 1: Write `SiteMailTest`.** `@QuarkusTest` with a static `@RegisterExtension GreenMailExtension(ServerSetupTest.SMTP)`, and settings pointing to `localhost:3025`, security `none`.
  - `sendsPlainTextUtf8`: subject `Привет`, the body arrives intact.
  - `afterCommitSendsOnlyOnCommit`: inside `QuarkusTransaction.requiringNew().run(...)`, `sendAfterCommit` then throw → no message. Without the throw → one message.
  - `failureIsRecordedNotThrown`: point at a closed port. `sendAfterCommit` in a committed transaction leaves `mail_last_error` starting with an ISO instant, and the caller gets no exception.
  - `reloadPicksUpNewSettings`.
- [ ] **Step 2: Run, expect FAIL. Add the dependencies, restart dev mode, implement `Mail`, `SiteMail` and `MailTexts`.**
- [ ] **Step 3: Write `MailResourceTest`.**
  - `passwordIsStoredEncryptedAndNeverReturned`: the GET body has no `password` key, and `secret` holds `smtp_password`.
  - `nullKeepsEmptyClearsPassword`, `clearingHostDisablesMail`, `testMailArrives`, `testMailFailureIs502`, `nonAdminIs403`.
- [ ] **Step 4: Implement `MailResource`. Run both classes, expect PASS.**
- [ ] **Step 5: Build the Security screen's Email section.**
  - `security.test.tsx` (fake API like `users.test.tsx`):
    - `rendersSavedPasswordPlaceholder`;
    - `testButtonShowsFailureMessage`: a 502 shows `The test email could not be sent: <message>`.
  - Add the route, the nav link and the `mayOpen` entry. Add Russian strings.
- [ ] **Step 6: Run vitest and `tsc --noEmit` (`bun run build` via Quinoa at the next dev reload). Commit.** `feat: SMTP settings in the admin with test mail`

### Task 7: Invites and email reset

**Files:**
- Modify: `admin/UsersResource.java`, `admin/AuthResource.java`, `webui/src/pages/{Users,Login}.tsx`, `webui/src/main.tsx`, `webui/src/pages/admin.ts` (public route), `ru.json`
- Create: `webui/src/pages/ForgotPassword.tsx`
- Test: `admin/UsersResourceTest.java`, `admin/PasswordResetTest.java`, `webui/src/pages/users.test.tsx`, `webui/src/pages/forgot.test.tsx`

**Interfaces:**
- Consumes: `SiteMail`, `MailTexts`, `SetPasswordLink.issue(id, ttl)`, `TokenRepository.issuedSince`.
- Produces: `POST /admin/api/users` response:
  - with mail configured: `{id, mailed: true}`. The invite is sent after commit, with `INVITE_TTL`, to the new user's email;
  - otherwise: `{id, mailed: false, setPasswordUrl}` with `MANUAL_TTL`, as before.
- Produces: `POST /admin/api/users/{id}/invite` (admin) returns 204 and mails after commit. It returns 409 `mail_not_configured`, 400 `no_email`, or 404.
- Produces: `POST /admin/api/users/{id}/password-link` unchanged, with `MANUAL_TTL`.
- Produces: public `POST /admin/api/password-reset {login, captcha, website}`:
  - always 204, after `limiter.blockedIp` (429) and, from Task 9, `captcha.verify`;
  - every request counts once against the IP via `limiter.ipFailure(ip)`;
  - it sends a mail with a `RESET_TTL` link only when the user exists, has an email, mail is configured, and `!tokens.issuedSince(id, SET_PASSWORD, now - 5 min)`;
  - a `login` longer than 100 characters still gets 204, and nothing is looked up.
- Produces: public `GET /admin/api/login-options` → `{mail: boolean}`. Task 9 adds the CAPTCHA fields.
- Produces: the `/forgot-password` screen, plus a "Forgot password?" link under the login button.
  - After submit with `mail: true` it shows `If an account matches, we sent a link to its email address. The link works for one hour.`
  - With `mail: false` the form isn't shown; it says `Email isn't set up on this site. Ask an administrator for a link to set your password.`
- Produces: on the Users screen, for users without a password and with an email, a "Send invite" button when mail is configured, next to "Copy link". A success shows `Invite sent to <email>.`

- [ ] **Step 1: Write the failing tests** (GreenMail as in Task 6).
  - `UsersResourceTest`: `createMailsInviteWhenMailConfigured` (the body contains `admin_url + "/admin/set-password?token="`, and the token works for 7 days: check `user_token.expires_at`), `createWithoutMailReturnsLink`, `inviteMailFailureKeepsTheUser` (closed SMTP port → 201, user present, `mail_last_error` set), `inviteRevokesEarlierLink`.
  - `PasswordResetTest`:
    - `unknownUserAndKnownUserSameAnswer`: same status and body, and only the known one gets mail;
    - `resetLinkIgnoresRequestHost`: send `Host: evil.example` and `X-Forwarded-Host: evil.example` from a trusted proxy address. The mailed link starts with `admin_url`;
    - `oneMailPerFiveMinutes`: two requests give one mail, and after the clock moves 6 min a second mail arrives. Use `MutableClock` only if `SessionRepository.now()` is clock-injectable; otherwise backdate `user_token.created_at` in the test;
    - `resetTokenExpiresInAnHour`;
    - `resetsCountAgainstTheIp`: the 21st request from one IP gives 429;
    - `withoutMailNothingIsSent`.
  - `forgot.test.tsx`: `showsAskAdminWhenMailIsOff`, `showsSentMessageAfterSubmit`.
  - `users.test.tsx`: `sendInviteButtonOnlyWithMail`.
- [ ] **Step 2: Run, expect FAIL. Implement backend, then UI.** `/forgot-password` joins the public routes beside `/login` and `/set-password` in `pages/admin.ts` and `Shell.tsx`.
- [ ] **Step 3: Run the classes, vitest and tsc. Expect PASS. Commit.** `feat: invite and password-reset email`

### Task 8: "Pending review" notifications

**Files:**
- Create: `mail/ReviewNotifications.java`
- Modify: `content/ItemService.java` (`update`), `store/AuthorRepository.java`
- Test: `mail/ReviewNotificationsTest.java`

**Interfaces:**
- Produces: `List<AuthorRecord> AuthorRepository.reviewers()`: administrators and editors with a non-empty email, in one query.
- Produces: `void ReviewNotifications.pendingReview(ItemRecord item, long actorId)`.
  - With mail configured, it calls `sendAfterCommit` for each reviewer except the actor.
  - The link is `admin_url + "/admin/edit/" + id`, or the path when `admin_url` is unset.
  - It uses `reviewSubject(siteTitle, itemTitle)` in each recipient's locale. The author is the actor's display name.
- `ItemService.update` calls it when the new status is `pending` and the stored status before the save was not `pending`.

- [ ] **Step 1: Write the failing tests.**
  - `contributorSubmitMailsAdminsAndEditors`: one admin, one editor, one author, one editor without email → exactly two recipients.
  - `resavingPendingSendsNothing`.
  - `editorSubmittingOwnPostSkipsThemselves`.
  - `rollbackSendsNothing`: an update refused with 409 sends nothing.
- [ ] **Step 2: Run, expect FAIL. Implement. Run, expect PASS. Commit.** `feat: email reviewers when an item is submitted for review`

### Task 9: CAPTCHA backend

**Files:**
- Create: `account/Captcha.java`, `account/CaptchaFailedException.java` (+ `ExceptionMapper` → 400 `{"error":"captcha_failed"}`), `admin/AltchaResource.java`, `admin/CaptchaResource.java`, `account/CaptchaCommand.java`
- Modify: `pom.xml` (`org.altcha:altcha`), `admin/AuthResource.java` (login, set-password, password-reset, login-options), `admin/SetupResource.java`, `StillpressCommand.java`, `application.properties`
- Test: `account/CaptchaTest.java`, `admin/CaptchaResourceTest.java`, `admin/CaptchaEnforcementTest.java`, `account/CaptchaCommandTest.java`

Port the logic from calit (`../calit/src/main/java/site/asm0dey/calit/booking/{CaptchaVerifier,AltchaResource,CaptchaProviderConfig}.java`): the timeouts, the `success` regex and the ALTCHA calls. Its config resolution doesn't carry over (see below).

**Interfaces:**
- Config (all optional):
  - `stillpress.captcha.provider` (env `STILLPRESS_CAPTCHA_PROVIDER`);
  - `stillpress.captcha.turnstile.site-key` and `.secret` (env `STILLPRESS_TURNSTILE_SITE_KEY`, `STILLPRESS_TURNSTILE_SECRET`);
  - `stillpress.captcha.turnstile.verify-url`, default `https://challenges.cloudflare.com/turnstile/v0/siteverify`.
- Produces: `@ApplicationScoped Captcha`:
  - `String provider()`: config if set, else setting `captcha_provider`, else `none`;
  - `boolean locked()`: config provider set. When locked, the Turnstile keys come only from config;
  - `Optional<String> turnstileSiteKey()`;
  - `void verify(String token, String honeypot, String ip)`: throws `CaptchaFailedException` when the honeypot is non-empty, or the provider check fails. Turnstile posts `secret`, `response` and `remoteip`; ALTCHA uses `Altcha.verifySolution(token, hmacKey(), true)`;
  - `String hmacKey()` = hex of `secrets.derive("altcha-hmac", 32)`;
  - `void save(String provider, String siteKey, String secret)`. `secret` null keeps the stored one. It throws `Refused(409,"captcha_locked")` when locked. For `turnstile` it needs a site key and a secret (400 `{error:"invalid", field}`), and checks the secret: siteverify with `response=stillpress-key-check`. An `error-codes` array containing `invalid-input-secret` gives 400 `invalid_turnstile_secret`, and a transport failure gives 502 `turnstile_unreachable`. Only after the check does it store `captcha_provider`, `turnstile_site_key` and the secret `turnstile_secret`;
  - `String privacyNotice(String locale)`: empty for `none`. For the others it is a sentence naming the provider and what it processes, in en/ru via a `@MessageBundle("captcha")`. Plan 03 places it.
- Produces:
  - public `GET /admin/api/altcha/challenge`: 404 unless the provider is `altcha`; 300 s expiry, `maxNumber` 100000, SHA-256;
  - `GET /admin/api/login-options` → `{mail, captcha: provider, turnstileSiteKey}`;
  - admin `GET /admin/api/captcha` → `{provider, turnstileSiteKey, secretSet, locked}`;
  - admin `PUT /admin/api/captcha` → `{provider, turnstileSiteKey, turnstileSecret}`;
  - CLI `stillpress captcha none`: sets the setting to `none` and prints `CAPTCHA is now off.` When locked it also prints to stderr `STILLPRESS_CAPTCHA_PROVIDER is set and overrides this; unset it and restart.` Exit 0.
- Request bodies gain `captcha` and `website`: `AuthResource.Credentials`, `NewPassword`, the reset body, and `SetupResource.Setup`. Verification order:
  - login: after the IP rate-limit check, before any lookup;
  - set-password and reset: first;
  - setup: after the token check.

- [ ] **Step 1: Write `CaptchaTest`.**
  - `noneAcceptsAnything`, `honeypotFailsUnderEveryProvider`.
  - `altchaRoundTrip`: create a challenge, solve it with `Altcha.solveChallenge` from the library, and verification passes. A tampered payload fails.
  - `turnstileUsesVerifyUrl` (WireMock as in `HttpMediaFetcherTest`: success → pass; `"success":false` → fail; timeout → fail).
- [ ] **Step 2: Write `CaptchaResourceTest`.**
  - `badTurnstileSecretIsRefused`: WireMock answers `invalid-input-secret` → 400, and the provider is unchanged.
  - `goodSecretSavesEncrypted`.
  - `envProviderLocksTheSetting`: a `@TestProfile` setting `stillpress.captcha.provider=altcha` → PUT gives 409, and GET shows `locked: true`.
  - `challenge404UnlessAltcha`.
- [ ] **Step 3: Write `CaptchaEnforcementTest`.**
  - With the provider `altcha`, login, set-password, reset and setup without a token each give 400 `captcha_failed`, and with a solved challenge they take the normal path.
  - `loginCaptchaFailureDoesNotCountAgainstTheAccount`.
  - `CaptchaCommandTest.cliWarnsWhenEnvOverrides`.
- [ ] **Step 4: Run, expect FAIL. Add `org.altcha:altcha`, restart dev mode, implement. Run all four classes and the full admin suite. Expect PASS.** Existing tests run with provider `none` and need no changes beyond the new nullable record fields.
- [ ] **Step 5: Commit.** `feat: CAPTCHA (none, turnstile, altcha) and honeypot on the public admin forms`

### Task 10: CAPTCHA in the admin UI

**Files:**
- Create: `webui/src/Captcha.tsx`, `webui/src/captcha.test.tsx`
- Modify: `webui/package.json` (`"altcha": "3.6.0"`, exact), `webui/src/pages/{Login,Setup,SetPassword,ForgotPassword,Security}.tsx`, `webui/src/theme.css` (`.hp` off-screen class), `ru.json`

**Interfaces:**
- Produces:
  - `type LoginOptions = { mail: boolean; captcha: 'none' | 'turnstile' | 'altcha'; turnstileSiteKey?: string }`, plus `useLoginOptions()`, fetched once per page;
  - `<Captcha options onToken={(t?: string) => void} />` renders nothing for `none`:
    - for `altcha`: `<altcha-widget challenge="/admin/api/altcha/challenge" name="captcha">`, imported from the `altcha` package. Its `statechange` event with `state === 'verified'` gives `detail.payload`;
    - for `turnstile`: it loads `https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit` once and calls `turnstile.render(el, {sitekey, callback})`;
    - it declares the `altcha-widget` JSX intrinsic element type;
  - `<Honeypot value onChange />`: an input named `website`, `tabIndex={-1}`, `autoComplete="off"`, inside a `div.hp` with `aria-hidden="true"`;
  - each of the four forms sends `captcha` and `website`. Submit stays disabled until a token exists when the provider isn't `none`. On 400 `captcha_failed` the form shows `The check didn't pass. Try again.` and resets the widget;
  - the Security screen's "CAPTCHA" section: a provider radio (None, Cloudflare Turnstile, ALTCHA (self-hosted)), with site key and secret fields for Turnstile;
    - when locked it shows `Set by STILLPRESS_CAPTCHA_PROVIDER on the server.` and the fields are disabled;
    - error text for `invalid_turnstile_secret`: `Cloudflare rejected this secret key.`

- [ ] **Step 1: Write `captcha.test.tsx`.**
  - `noneRendersNothing`.
  - `altchaVerifiedEventYieldsToken`: dispatch a `statechange` `CustomEvent` on the element, and `onToken` receives the payload.
  - `turnstileScriptLoadsOnce`: two mounts → one `<script>`.
  - `loginDisabledUntilToken`.
- [ ] **Step 2: Run vitest, expect FAIL. Implement. Run vitest and tsc, expect PASS.**
- [ ] **Step 3: Manual check in the browser (Playwright MCP), dev mode.**
  - With provider `altcha`, log in through the widget. This is the regression calit hit: widget 3.4.0 never accepted v1 challenges.
  - Record the screen states in the results file.
- [ ] **Step 4: Commit.** `feat: CAPTCHA widgets and honeypot in the admin forms`

### Task 11: Acceptance, docs, results

**Files:** `README.md`, `docs/superpowers/plans/ROADMAP.md`, create `docs/superpowers/plans/2026-11-14-stillpress-02c-1-results.md`.

- [ ] **Step 1: Update `README.md`.**
  - Keys: location, back them up with the database, `STILLPRESS_KEYS_DIR`, `stillpress secrets reset`.
  - SMTP setup in the admin.
  - `admin_url` and cutover: change the Admin address when the site moves.
  - CAPTCHA env vars and `stillpress captcha none`.
  - The new CLI commands.
- [ ] **Step 2: Update `ROADMAP.md`.**
  - Mark each 02c carry-over item done (with the task), and "an over-long password counts against the account's login bucket" as already fixed by 02b (`AuthResourceTest.oversizedCredentialsAre401AndCountAgainstTheIpOnly`).
  - Add to "Replicas (PostgreSQL only)": shared keyset override required; SMTP reload and the limiter are per node.
  - Mark 02c-1 done, with links to this plan and its results.
- [ ] **Step 3: Run the full verify.** `mise exec -- mvn -q spotless:apply verify` (H2), then the `pg` profile tests the project already runs. Expect BUILD SUCCESS.
- [ ] **Step 4: Run the end-to-end acceptance in dev mode, through the browser, using Mailpit or GreenMail standalone as SMTP.**
  - Import `blog.xml`: the user supplies its path.
  - Set the Admin address and SMTP.
  - Invite an imported author, open the mailed link, set a password, log in.
  - Request a reset with a forged `Host` and confirm the link host.
  - Submit a post for review as a contributor and see the editor's mail.
  - Switch CAPTCHA to ALTCHA and log in; switch it to Turnstile with a bad secret (refused); run `stillpress captcha none`.
  - Delete the keys dir with an SMTP password stored → `serve` refuses and prints the message; `secrets reset` → starts.
- [ ] **Step 5: Write the results file** (format of `2026-11-14-stillpress-02b-followup-results.md`: outcome, per-task notes, problems, deferred items). **Commit and push.** `docs: 02c-1 results; roadmap`
