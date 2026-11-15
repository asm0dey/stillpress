# 02c-2: TOTP, passkeys, mandatory 2FA — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users can add one authenticator app (TOTP) and any number of passkeys. A passkey alone is a full sign-in, and password sign-in continues to a second step for anyone with 2FA. An administrator can make 2FA mandatory. A lost device is cleared from the users screen or the CLI. Every item in the roadmap's "Carried over from 02c-1 (for 02c-2)" is closed first; the four "Early in 02c-2" items lead.

**Architecture:**
- **Factors.** A new `totp_factor` table holds each user's seed, encrypted through `Secrets` (Tink AEAD, associated data `totp:<userId>`). A new `passkey` table holds WebAuthn public keys. `TwoFactorRepository` owns both tables.
- **Passkeys.** `quarkus-security-webauthn` is used only through the `WebAuthnSecurity` bean. Its challenge endpoints (`GET /q/webauthn/*-options-challenge`) and its `webauthn.js` client are kept. Registration and login go to our own endpoints under `/admin/api`, which call the bean's `JsonObject` overloads and issue `sp_session`. The extension's own login and register endpoints stay disabled, and its cookie is never set.
- **Second step.** A correct password for a user with 2FA does not create a session. It creates a 5-minute `second_factor` token in `user_token`, carried in an HttpOnly `sp_2fa` cookie scoped to `/admin/api/login`. The TOTP or passkey step consumes that token and only then issues `sp_session`.
- **Mandatory 2FA.** A `require_2fa` setting. `SessionIdentityProvider` marks identities that still have to enroll, and a REST request filter fences them to the enrollment endpoints.
- **Tokens.** `user_token` gets a unique `(user_id, purpose)`. Issuing a token is one upsert, and the reset-mail throttle is part of that same statement.

**Tech Stack:** existing stack (Java 25, Quarkus 3.40, jOOQ, Flyway, H2/PostgreSQL, Tink, React + TypeScript + Bun). New:
- `io.quarkus:quarkus-security-webauthn` (platform BOM);
- test-only `io.quarkus:quarkus-test-security-webauthn` (platform BOM; `WebAuthnHardware` makes registration and login JSON in tests);
- `com.eatthepath:java-otp:1.0.0` (RFC 6238; released 2026-08-18, so maintained);
- `io.nayuki:qrcodegen:1.8.0` (QR matrix, no dependencies; the QR is rendered on the server as SVG, so the seed never reaches third-party JS).

**Spec:** `docs/superpowers/specs/2026-11-14-stillpress-02c-account-security-design.md` (sections "Sign-in", "Mandatory 2FA", "Lost 2FA device", "Secrets", "Left to the builder"), `docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md` §6.2.2, `docs/superpowers/plans/ROADMAP.md` ("Carried over from 02c-1 (for 02c-2)": every bullet).

## Global Constraints

- Everything in the Global Constraints of 02c-1 (`2026-11-14-stillpress-02c-1-keys-mail-captcha.md`) still holds. That includes the binding precedents, which go into every implementer and reviewer prompt:
  - AssertJ on values, never `assertThat(boolean).isTrue()` for something that has a value;
  - set-based SQL;
  - no test-only production interfaces;
  - framework first;
  - trust jOOQ;
  - read jOOQ results by field;
  - `import module java.base;`;
  - Tink, never a hand-rolled cipher;
  - Quarkus docs via `quarkus_searchDocs`;
  - tests via `devui-testing_runTest`;
  - `quarkus_stop` + `quarkus_start` after a `pom.xml` change;
  - every admin string in `__('…', 'stillpress')` with a `ru.json` entry.
- 02c-1's rules on enumeration-safe reset, "mail never fails the action" and "no link from a request host" still hold. Task 2 makes the reset's 204 independent of the account, in timing as well as in body.
- Work on `main`; push every commit to `origin`.
- Migrations `V9__user_token_unique.sql` (Task 2) and `V10__two_factor.sql` (Task 5) in `db/migration/common`, portable SQL, then `mise exec -- mvn -q -Pcodegen generate-sources`. Add `totp_factor` and `passkey` to `TestStore.clear()` (before `author`).
- **One session mechanism.** Only `sp_session` grants access. Never call `WebAuthnSecurity.rememberUser`. Keep `quarkus.webauthn.enable-login-endpoint` and `enable-registration-endpoint` at their default `false`.
- **The second step is not a session.** No endpoint except `/admin/api/login/totp` and `/admin/api/login/passkey-step` reads `sp_2fa`.
- **There is always a CLI way back in:** `stillpress user reset-2fa <login>` and `stillpress user reset-password <login>` work with mandatory 2FA on, for the last administrator too.
- Constants pinned by this plan:
  - TOTP: 30 s step, 6 digits, HmacSHA1, 20-byte random seed, accepts the previous, current and next step, and never accepts a step at or below `last_step`;
  - second-step token TTL 5 minutes, cookie `sp_2fa`, `Path=/admin/api/login`, `HttpOnly`, `SameSite=Strict`, `Max-Age=300`, `Secure` when the request is https;
  - passkey name 1–100 characters after trimming;
  - WebAuthn: the extension defaults (`user-verification=required`, `resident-key=required`, `attestation=none`). `quarkus.webauthn.origins` and `relying-party.id` stay unset: the extension then uses the request's origin (`WebAuthnSecurity.getAllowedOrigins`), which follows the forwarded host behind nginx, so the pre-cutover host and the final domain both work. Each passkey is bound to the host it was registered on.
  - `quarkus.webauthn.relying-party.name=Stillpress`.

## Review Focus

1. **A TOTP code used twice.** A code that signed someone in must not sign in a second time within its 90-second window, even from two concurrent requests. Task 6 test `totpCodeCannotBeReused`; the guard is one conditional `UPDATE`.
2. **The second-step cookie used as something else.** Its value sent as `sp_session` is anonymous. A passkey of another user does not finish someone's second step. An expired `sp_2fa` gives 401 `second_step_expired`. Task 8 tests `secondStepTokenIsNotASession`, `anotherUsersPasskeyFailsTheSecondStep` and `expiredSecondStepIs401`.
3. **A user who must enroll calls other APIs directly.** With mandatory 2FA on, a session without a factor gets 403 `enroll_2fa` from every REST endpoint except session, locale, logout and `/2fa/**`. Task 9 test `enrollRequiredSessionIsFencedToEnrollment`.
4. **Locking the site.** Each of these must leave a way in:
   - An administrator without 2FA can't turn mandatory 2FA on (409 `admin_needs_2fa`).
   - A user can't remove their last factor while it is on (409 `last_factor`).
   - The CLI clears the last administrator's factors.
   - A Turnstile site key that doesn't match its secret is refused on save, so it can't lock everyone out.
   
   Task 9 tests `adminWithout2faCannotRequireIt` and `lastFactorCannotBeRemovedWhileRequired`; Task 10 test `cliClearsTheLastAdministrator`; Task 3 test `mismatchedSiteKeyIsRefused`.
5. **Two resets at once, or a lost keyset with only TOTP seeds.** Two concurrent reset requests for one account send one mail and leave one live token (Task 2 test `concurrentResetsMailOnce`). With a lost keyset and only TOTP seeds stored, `serve` refuses to start, and `secrets reset` drops the TOTP enrollments but keeps the passkeys (Task 5 tests `serveRefusesFreshKeysWithOnlyTotpSeeds` and `resetDropsTotpKeepsPasskeys`).

---

## File Structure

Paths are relative to `src/main/java/site/asm0dey/stillpress/`, `src/main/resources/` or `src/main/webui/src/` as the extension shows. Tests mirror them.

- `account/Captcha.java`, `StillpressCommand.java`, `store/TokenRepository.java`, `account/SetPasswordLink.java`, `admin/UsersResource.java`, `admin/AuthResource.java` (Task 1).
- `db/migration/common/V9__user_token_unique.sql`, `store/TokenRepository.java`, `admin/AuthResource.java` (reset) (Task 2).
- `account/Captcha.java`, `admin/CaptchaResource.java`, `admin/AuthResource.java` (`loginOptions`) (Task 3).
- `Captcha.tsx`, `pages/{Login,SetPassword,ForgotPassword,Setup,Security}.tsx` (Task 4).
- `db/migration/common/V10__two_factor.sql`, `store/TwoFactorRepository.java`, `account/Secrets.java`, `account/SecretsCommand.java` (Task 5).
- `account/Base32.java`, `account/Qr.java`, `account/Totp.java`, `admin/TwoFactorResource.java` (Task 6).
- `account/Passkeys.java` (the `WebAuthnUserProvider`), `account/SessionAuthMechanism.java`, `config/KeysConfigSourceFactory.java`, `serve/NginxConfig.java`, `application.properties`, `admin/TwoFactorResource.java` (Task 7).
- `admin/AuthResource.java`, `store/TokenRepository.java` (Task 8).
- `account/TwoFactorPolicy.java`, `admin/TwoFactorPolicyResource.java`, `admin/EnrollmentGate.java`, `account/SessionIdentityProvider.java`, `store/SessionRepository.java` (Task 9).
- `admin/UsersResource.java`, `account/UserCommand.java` (Task 10).
- `webauthn.ts`, `api.ts`, `main.tsx`, `pages/Login.tsx`, `pages/SetPassword.tsx` (Task 11).
- `pages/TwoFactor.tsx`, `pages/admin.ts`, `Shell.tsx`, `pages/Security.tsx`, `pages/Users.tsx` (Task 12).
- `README.md`, `docs/superpowers/plans/ROADMAP.md`, `docs/superpowers/plans/2026-11-15-stillpress-02c-2-results.md` (Task 13).

---

### Task 1: CAPTCHA checks at startup; a reset keeps a pending invite

Roadmap "Early in 02c-2", backend half. This also closes the CAPTCHA bullet's "invalid provider fails on first use" and "env-locked Turnstile without keys", and the reset bullet's "replaces a pending 7-day invite".

**Files:**
- Modify: `account/Captcha.java`, `StillpressCommand.java` (`ServeCommand`), `store/TokenRepository.java`, `account/SetPasswordLink.java`, `admin/UsersResource.java` (invite), `admin/AuthResource.java` (`setPassword`)
- Test: `account/CaptchaTest.java`, `account/CaptchaCommandTest.java`, `admin/PasswordResetTest.java`, `admin/AuthResourceTest.java`

**Interfaces:**
- Produces: `Optional<String> Captcha.environmentProblem()` returns:
  - `STILLPRESS_CAPTCHA_PROVIDER must be none, turnstile or altcha: <value>` for an unknown value;
  - `STILLPRESS_CAPTCHA_PROVIDER is turnstile, but STILLPRESS_CAPTCHA_TURNSTILE_SITE_KEY and STILLPRESS_CAPTCHA_TURNSTILE_SECRET must both be set` when either env key is missing or blank;
  - empty otherwise.

  The constructor no longer throws, so `captcha none` can still start and print its override warning.
- Produces: `ServeCommand.run()` prints the problem to stderr and calls `Quarkus.asyncExit(1)` before `waitForExit`, the same way it handles the keyset guard.
- Produces: `TokenRepository.INVITE = "invite"`. `SET_PASSWORD` stays for reset mail, CLI reset and "Copy link". `create` still revokes only tokens of the same purpose.
- Produces: `Optional<Long> TokenRepository.consumeAny(String raw, Set<String> purposes)`. `setPassword` calls it with `{INVITE, SET_PASSWORD}`. On success it calls the new `void TokenRepository.deleteAll(long userId, Set<String> purposes)`, so the other link dies too.
- Produces: `String SetPasswordLink.issue(long userId, String purpose, Duration ttl)`. It replaces the two-argument form, and every call site passes its purpose: invite → `INVITE`, everything else → `SET_PASSWORD`.

- [ ] **Step 1: Write the failing tests.**
  - `CaptchaTest.unknownEnvProviderIsAStartupProblem`: construct `Captcha` with `Optional.of("recaptcha")`. `environmentProblem()` contains `must be none, turnstile or altcha: recaptcha`.
  - `CaptchaTest.envTurnstileWithoutKeysIsAStartupProblem`: env provider `turnstile` with no secret gives a problem naming both variables. With both keys set it is empty.
  - `CaptchaTest.serveExitsOnEnvProblem`: unit-test `ServeCommand.check(Keys, Secrets, Captcha)` (extend the existing guard method). It returns 1 and the problem text.
  - `PasswordResetTest.resetKeepsAPendingInvite`: send an invite, request a reset for the same user, then both tokens work until one is used. After the first set-password, the second gives 410.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Grep every caller of `SetPasswordLink.issue` and `TokenRepository.consume` before you edit.
- [ ] **Step 4: Run the four classes plus `UsersResourceTest`, `UserCommandTest` and `CaptchaEnforcementTest`. Expect PASS. Commit.** `fix: fail fast on a bad CAPTCHA environment; a reset no longer revokes a pending invite`

### Task 2: Tokens: one live token per purpose, an atomic reset throttle, a reset that answers at once

Closes the reset bullet's timing side channel, "two concurrent resets can both mail", and "two concurrent `create` calls can leave two live tokens".

**Files:**
- Create: `db/migration/common/V9__user_token_unique.sql`
- Modify: `store/TokenRepository.java`, `admin/AuthResource.java` (`passwordReset`, `mailReset`)
- Test: `store/TokenRepositoryTest.java` (create if absent), `admin/PasswordResetTest.java`

**Interfaces:**
- Produces: V9.
  - First, delete duplicate `(user_id, purpose)` rows, keeping the newest: a `DELETE … WHERE EXISTS` a row with the same pair and a later `created_at`, or the same `created_at` and a greater `id`.
  - Then `CREATE UNIQUE INDEX uq_user_token_user_purpose ON user_token (user_id, purpose)`.
- Produces: `TokenRepository.create(userId, purpose, ttl)` is one upsert: jOOQ `insertInto(…).onConflict(USER_ID, PURPOSE).doUpdate().set(id, expires_at, created_at)`. No delete-then-insert.
- Produces: `Optional<String> TokenRepository.createUnlessIssuedSince(long userId, String purpose, Duration ttl, Instant since)`. It is the same upsert with `.where(USER_TOKEN.CREATED_AT.lt(since))` on the update, and returns the raw token only when a row was inserted or updated. `issuedSince` goes; grep its callers.
- Produces: `SetPasswordLink.issueUnlessIssuedSince(long userId, String purpose, Duration ttl, Instant since)` → `Optional<String>`.
- Produces: `passwordReset` validates its input, checks and counts the IP limit, verifies the CAPTCHA, then hands the login to a worker and answers 204. It makes no repository call itself. The worker is Vert.x `executeBlocking`, as in `SiteMail.sendAfterCommit`, inside `QuarkusTransaction.requiringNew()`. It does the lookup, `issueUnlessIssuedSince(…, now - 5 min)` and the send; any exception is logged at WARN.

- [ ] **Step 1: Write the failing tests.**
  - `TokenRepositoryTest.concurrentCreatesLeaveOneToken`: two threads `create` for one user and purpose. One `user_token` row is left, and exactly one of the two raw tokens consumes.
  - `TokenRepositoryTest.throttleIsAtomic`: two threads `createUnlessIssuedSince` with `since = now - 5 min` on a user without a token. Exactly one returns a token.
  - `PasswordResetTest.concurrentResetsMailOnce`: two concurrent `POST /password-reset` for one account. Await the worker (Awaitility, as the 02c-1 mail tests do), then the mailbox has exactly one message.
  - The timing itself has no test. The reviewer checks that `passwordReset` calls no repository and no `SetPasswordLink` method.
  - Keep the 02c-1 tests `resetLinkIgnoresRequestHost` and the 5-minute limit green; they now await the worker.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement V9, codegen, the repository and the endpoint.**
- [ ] **Step 4: Run both classes plus `AuthResourceTest`, `UsersResourceTest` and `pg/PostgresAccountsTest` (the upsert on PostgreSQL). Expect PASS. Commit.** `fix: one live token per purpose; the reset throttle and lookup leave the request path`

### Task 3: Turnstile: check the site key on save; no stale site key

Closes "the Turnstile site key is never checked on save" and "`login-options` returns a stale `turnstileSiteKey`".

**Files:**
- Modify: `account/Captcha.java` (`save`), `admin/CaptchaResource.java` (`Update`), `admin/AuthResource.java` (`loginOptions`)
- Test: `admin/CaptchaResourceTest.java`, `admin/AuthResourceTest.java`

**Interfaces:**
- Produces: `CaptchaResource.Update` gains `String turnstileToken`, a token the admin's browser solved with the new site key.
- Produces: `Captcha.save(provider, siteKey, secret, testToken)`. For `turnstile` it siteverifies `testToken` with the new secret (no `remoteip`) and needs `"success": true`. It replaces the `stillpress-key-check` probe, which proved only the secret.
  - A missing token → 400 `{"error":"invalid","field":"turnstileToken"}`.
  - `invalid-input-secret` → 400 `invalid_turnstile_secret` (unchanged).
  - Any other failure → 400 `turnstile_key_mismatch`.
  - Unreachable → 502 `turnstile_unreachable` (unchanged).
- Produces: `loginOptions` returns `turnstileSiteKey` only while the provider is `turnstile`, and `null` otherwise.

- [ ] **Step 1: Write the failing tests.**
  - `CaptchaResourceTest.mismatchedSiteKeyIsRefused`: the siteverify stub answers `{"success":false,"error-codes":["invalid-input-response"]}` with HTTP 200. The PUT gives 400 `turnstile_key_mismatch`, and the provider is unchanged.
  - `CaptchaResourceTest.missingTestTokenIsRefused`.
  - `CaptchaResourceTest.matchingPairIsSaved`: the stub answers success. The provider is `turnstile`, and the stub got the new secret and the test token.
  - Update the 02c-1 tests that relied on the key-check probe; the bad-secret stub keeps answering HTTP 400.
  - `AuthResourceTest.loginOptionsHasNoStaleSiteKey`: save Turnstile, switch to ALTCHA, then `turnstileSiteKey` is null.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** The Security screen sends the token in Task 4.
- [ ] **Step 4: Run both classes plus `CaptchaTest`, expect PASS. Commit.** `fix: a Turnstile site key is checked with a solved token on save; no stale site key in login-options`

### Task 4: CAPTCHA in the admin UI: load failure, Russian ALTCHA, the remaining widget fixes

Roadmap "Early in 02c-2", front-end half, plus the rest of the admin UI bullet.

**Files:**
- Modify: `Captcha.tsx`, `pages/{Login,SetPassword,ForgotPassword,Setup}.tsx` (the reset call), `pages/Security.tsx` (Turnstile test widget), `api.ts` (`CaptchaSettings` PUT), `i18n/ru.json`
- Test: `captcha.test.tsx`, `forgot.test.tsx`, `security.test.tsx`

**Interfaces:**
- Produces: when `loadTurnstile()` rejects, `Captcha` calls `onError`. `useCaptcha()` exposes `error: string | undefined` and renders `<p role="alert" className="error">` with `Could not load the CAPTCHA. Check your connection and reload the page.`; `ready` stays false.
- Produces: the ALTCHA widget gets `language={locale()}` (from `i18n/index.ts`), and `Captcha.tsx` imports `altcha/i18n/ru`. altcha 3.6.0 ships `dist/i18n/ru.js`, which beats feeding our own strings. Add `language` to the JSX `IntrinsicElements` declaration.
- Produces: `challengeFetch(input, init)` keeps the query string and passes `init` through to `adminFetch(path + search, init)`.
- Produces: `useCaptcha().failed(err: ApiError)` replaces `reset()` at the four call sites. It remounts the widget only when the server may have spent the token. It doesn't remount on status 0 (network), 429, or `error === 'csrf'`, which are all refused before `verify`.
- Produces: `useLoginOptions()` returns `{ options, failed }`. A failed fetch no longer pretends `{mail: true, captcha: 'none'}`:
  - `ForgotPassword` shows `Could not reach the server. Reload the page and try again.` and no form;
  - the other forms keep the current fallback (the server still checks).
- Produces: the Security screen's Turnstile form renders a Turnstile widget with the typed site key (a `Captcha` with `{captcha: 'turnstile', turnstileSiteKey}`) and sends its token as `turnstileToken`. Save stays disabled until it is solved. `turnstile_key_mismatch` → `The site key doesn't match this secret key.`

- [ ] **Step 1: Write the failing tests.**
  - `captcha.test.tsx`:
    - `turnstileLoadFailureIsShown`: make the appended `<script>` fire `error`. The alert appears, and the button stays disabled.
    - `altchaFollowsTheAdminLanguage`: locale `ru` → `language="ru"` on `altcha-widget`.
    - `challengeFetchKeepsQueryAndInit`: calling the configured fetch with `/admin/api/altcha/challenge?x=1` and `{method: 'GET', signal}` reaches `fakeApi` as `GET /altcha/challenge?x=1`.
    - `tokenKeptOnRateLimit`: a 429 from `/login` leaves the button enabled with the same token; a `captcha_failed` disables it.
    - Rewrite `loginDisabledUntilToken`. Await the widget element with `findBy`/`waitFor` before dispatching, so the test no longer races the mount. Then also dispatch `statechange` with `state: 'expired'` and expect the button disabled again.
  - `forgot.test.tsx`: `loginOptionsFailureIsNotMailOn`.
  - `security.test.tsx`: `turnstileSaveSendsTestToken`.
- [ ] **Step 2: Run** `bun test` **(from `src/main/webui`), expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `bun test` and `bunx tsc --noEmit`, expect PASS. Commit.** `fix(admin): CAPTCHA load errors, Russian ALTCHA, token kept when unspent, site-key check on save`

### Task 5: Factor storage, and TOTP seeds under the keyset guard

**Files:**
- Create: `db/migration/common/V10__two_factor.sql`, `store/TwoFactorRepository.java`
- Modify: `account/Secrets.java`, `account/SecretsCommand.java`, `src/test/java/.../TestStore.java`
- Test: `store/TwoFactorRepositoryTest.java`, `account/SecretsTest.java`, `account/SecretsCommandTest.java`

**Interfaces:**
- Produces: V10.

  ```sql
  CREATE TABLE totp_factor (
      user_id BIGINT PRIMARY KEY REFERENCES author (id),
      ciphertext TEXT NOT NULL,
      confirmed BOOLEAN NOT NULL,
      last_step BIGINT NOT NULL,
      created_at TIMESTAMP NOT NULL
  );
  CREATE TABLE passkey (
      credential_id VARCHAR(255) PRIMARY KEY,
      user_id BIGINT NOT NULL REFERENCES author (id),
      name VARCHAR(100) NOT NULL,
      public_key TEXT NOT NULL,
      algorithm BIGINT NOT NULL,
      aaguid VARCHAR(36),
      sign_count BIGINT NOT NULL,
      created_at TIMESTAMP NOT NULL,
      last_used_at TIMESTAMP
  );
  CREATE INDEX ix_passkey_user ON passkey (user_id);
  ```

  - `totp_factor.ciphertext` is base64 AEAD output with associated data `totp:<user_id>`.
  - `last_step` is the highest accepted time step, 0 before first use.
  - `passkey.public_key` is base64 X.509. The columns mirror `WebAuthnCredentialRecord.RequiredPersistedData`; read that record's field types in the extension source before writing the mapper.
- Produces: `Secrets`:
  - `String encrypt(String name, String value)` and `Optional<String> decrypt(String name, String ciphertext)` (the existing private logic made public);
  - `any()` is also true when any `totp_factor` row exists.
- Produces: `TwoFactorRepository`:
  - `Optional<TotpFactorRecord> totp(long userId)`
  - `void putTotp(long userId, String ciphertext)`: upserts an unconfirmed row with `last_step = 0`
  - `void confirmTotp(long userId)`
  - `boolean advanceTotp(long userId, long step)`: `UPDATE … SET last_step = :step WHERE user_id = :id AND confirmed AND last_step < :step`; true when one row changed
  - `int deleteTotp(long userId)`, `int deleteAllTotp()`
  - `List<PasskeyRecord> passkeys(long userId)`, `Optional<PasskeyRecord> passkey(String credentialId)`
  - `void addPasskey(long userId, String name, WebAuthnCredentialRecord.RequiredPersistedData data)`
  - `void usePasskey(String credentialId, long signCount)`: sets `sign_count` and `last_used_at`
  - `int deletePasskey(long userId, String credentialId)`
  - `int factorCount(long userId)`: confirmed TOTP plus passkeys, in one statement
  - `boolean hasFactor(long userId)`: `factorCount > 0`
  - `int clear(long userId)`: deletes both, returns the total
- Produces: `secrets reset` also calls `deleteAllTotp()`. It prints `Deleted N stored secrets and M authenticator apps. Re-enter the SMTP password and CAPTCHA secret in the admin; users with an authenticator app must add it again. Passkeys are kept.`

- [ ] **Step 1: Write the failing tests.**
  - `TwoFactorRepositoryTest.advanceTotpIsMonotonic`: after `advanceTotp(id, 10)`, `advanceTotp(id, 10)` and `advanceTotp(id, 9)` are false and `advanceTotp(id, 11)` is true. An unconfirmed row never advances.
  - `TwoFactorRepositoryTest.factorCountIgnoresUnconfirmedTotp`: unconfirmed TOTP plus two passkeys gives 2; after `confirmTotp` it gives 3.
  - `SecretsTest.totpCiphertextIsNameBound`: `decrypt("totp:2", encrypt("totp:1", "x"))` is empty.
  - `SecretsCommandTest.serveRefusesFreshKeysWithOnlyTotpSeeds`: with no `secret` rows and one `totp_factor` row, `ServeCommand.check` returns 1.
  - `SecretsCommandTest.resetDropsTotpKeepsPasskeys`: the counts in the output are right, `totp_factor` is empty, and `passkey` is unchanged. Update the 02c-1 tests that pin the old message.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement V10, codegen, the repository and the `Secrets` changes.**
- [ ] **Step 4: Run the three classes, expect PASS. Commit.** `feat: storage for TOTP and passkey factors; TOTP seeds count as stored secrets`

### Task 6: TOTP enrollment

**Files:**
- Create: `account/Base32.java`, `account/Qr.java`, `account/Totp.java`, `admin/TwoFactorResource.java`
- Modify: `pom.xml` (java-otp, qrcodegen)
- Test: `account/Base32Test.java`, `account/TotpTest.java`, `admin/TwoFactorResourceTest.java`

**Interfaces:**
- Produces: `static String Base32.encode(byte[] data)`: RFC 4648 alphabet, unpadded. The JDK has none, and no codec is on the classpath.
- Produces: `static String Qr.svg(String text)`: `QrCode.encodeText(text, QrCode.Ecc.MEDIUM)`, a 4-module border, one `<path>` of black modules on a white `<rect>`, `viewBox` in modules, no width or height.
- Produces: `@ApplicationScoped Totp`, which takes the existing `Clock` bean like `LoginLimiter`:
  - `record Enrollment(String secret, String uri, String qrSvg)`;
  - `Enrollment start(long userId, String login)`. The URI is `otpauth://totp/<issuer>:<login>?secret=<b32>&issuer=<issuer>&algorithm=SHA1&digits=6&period=30`, where `<issuer>` is the URL-encoded `title` setting (default `Stillpress`) and `<login>` is URL-encoded;
  - `boolean confirm(long userId, String code)`: verifies against the unconfirmed seed, then confirms it and records the step;
  - `boolean verify(long userId, String code)`: confirmed seed only. It tries steps `now-1..now+1` and returns true on the first match whose `advanceTotp` succeeds. A code that isn't exactly 6 ASCII digits is false without any crypto.
- Produces: `@Path("/admin/api/2fa") @Authenticated TwoFactorResource`:
  - `GET /` → `Factors(boolean totp, List<PasskeyView> passkeys, boolean required)`, with `PasskeyView(String id, String name, String createdAt, String lastUsedAt)` (ISO-8601 UTC strings; `lastUsedAt` may be null). `required` is false until Task 9;
  - `POST /totp` → 200 `Enrollment`; 409 `totp_exists` when a confirmed one exists;
  - `POST /totp/confirm` with `Code(String code)` → 204; 400 `invalid_code`;
  - `DELETE /totp` → 204; 404 when none. Task 9 adds 409 `last_factor`.

- [ ] **Step 1: Write the failing tests.**
  - `Base32Test.rfc4648Vectors`: `""`→`""`, `"f"`→`"MY"`, `"foobar"`→`"MZXW6YTBOI"`.
  - `TotpTest.rfc6238Vector`: the RFC 6238 SHA1 vector (seed `12345678901234567890`, T=59 s → `287082` as the 6-digit code) through `verify`, with a `MutableClock` at 59 s.
  - `TotpTest.totpCodeCannotBeReused`: the same valid code twice gives `true`, then `false`. Two threads verifying one code concurrently: exactly one `true`.
  - `TotpTest.acceptsOneStepOfDrift` (±30 s yes, ±60 s no), `TotpTest.rejectsNonDigits` (`"12345a"`, `" 123456"`, `"1234567"`).
  - `TwoFactorResourceTest.enrollConfirmAndList`:
    - `POST /totp` gives a `secret` matching `[A-Z2-7]{32}`, a `uri` starting with `otpauth://totp/`, and a `qrSvg` starting with `<svg`;
    - confirm with a code generated from `secret`, then `GET /2fa` has `totp: true`;
    - the stored ciphertext doesn't contain the secret.
  - `TwoFactorResourceTest.secondEnrollmentIs409`, `TwoFactorResourceTest.anonymousIs401`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Add the dependencies, then `quarkus_stop` + `quarkus_start`. Use `TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1")` with `new SecretKeySpec(seed, "HmacSHA1")`. Check the 1.0.0 API in its javadoc; don't guess.
- [ ] **Step 4: Run the classes, expect PASS. Commit.** `feat: TOTP enrollment with a server-rendered QR code`

### Task 7: Passkey registration through WebAuthnSecurity

**Files:**
- Create: `account/Passkeys.java`
- Modify: `pom.xml`, `application.properties`, `account/SessionAuthMechanism.java`, `config/KeysConfigSourceFactory.java`, `serve/NginxConfig.java` (`DYNAMIC_PREFIXES`), `admin/TwoFactorResource.java`
- Test: `admin/PasskeyRegistrationTest.java`, `account/SessionAuthTest.java`, `admin/CsrfSignatureTest.java`, `serve/NginxConfigTest.java`

**Interfaces:**
- Produces: `@ApplicationScoped Passkeys implements WebAuthnUserProvider`, read-only. `findByUsername(login)` returns that user's passkeys and `findByCredentialId(id)` one passkey, both as `WebAuthnCredentialRecord.fromRequiredPersistedData(...)` with the user's login as username. It keeps the default `store` and `update`.
- Produces: `TwoFactorResource`:
  - `POST /passkeys` with `NewPasskey(String name, JsonObject credential)` calls `webAuthnSecurity.register(<session login>, credential, ctx)`. The challenge cookie was issued for the username the client sent, so a mismatch fails inside the bean. Then `addPasskey`. Answers 201 `PasskeyView`; 400 `invalid_passkey` on any failure of the `Uni`; 400 `{"error":"invalid","field":"name"}` for a bad name;
  - `DELETE /passkeys/{id}` → 204; 404 when it isn't this user's.
- Produces: `SessionAuthMechanism.getPriority()` returns `2000`, above the WebAuthn mechanism's 1000. Otherwise its challenge (a redirect to `login-page`) could replace our 401.
- Produces: `quarkus.http.auth.session.encryption-key` = hex of `prf.computePrimary("webauthn-cookie-key", 32)`, supplied by `KeysConfigSourceFactory` next to the CSRF key. The challenge cookie then survives a restart, and nobody without the keyset can forge the extension's cookie.
- Produces: `NginxConfig.DYNAMIC_PREFIXES` gains `/q/webauthn/`. That covers the two challenge GETs and `webauthn.js`.

- [ ] **Step 1: Write the failing tests.**
  - `PasskeyRegistrationTest.registerListAndDelete`: logged in, `GET /q/webauthn/register-options-challenge?username=<login>` keeps the challenge cookie. `WebAuthnHardware.makeRegistrationJson(challenge)` → `POST /admin/api/2fa/passkeys` gives 201. `GET /2fa` lists it, and `DELETE` gives 204.
  - `PasskeyRegistrationTest.challengeForAnotherLoginIsRefused`: a challenge fetched for `other` and posted by `admin` gives 400 `invalid_passkey`.
  - `SessionAuthTest.anonymousApiStays401WithWebAuthn`: `GET /admin/api/session` without a cookie gives 401 with no `Location`.
  - `SessionAuthTest.webauthnCookieNeverAuthenticates`: a request with only `quarkus-credential=anything` gives 401.
  - `CsrfSignatureTest.sessionEncryptionKeyFromKeyset`: the config value has 64 hex characters and equals the hex of `secrets.derive("webauthn-cookie-key", 32)`.
  - `NginxConfigTest`: the rendered include has `location ^~ /q/webauthn/`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Add the extension and test helper, then `quarkus_stop` + `quarkus_start`. Load the `quarkus_skills` entry for `security-webauthn` first. In the REST methods, get `RoutingContext` as a method parameter like the guide's `LoginResource`, and block on the `Uni` with `.await().atMost(Duration.ofSeconds(10))`.
- [ ] **Step 4: Run the classes, then the full suite. Every existing 401 test stays green. Commit.** `feat: passkey registration through WebAuthnSecurity, on sp_session only`

### Task 8: Two-step password login and passkey sign-in

**Files:**
- Modify: `admin/AuthResource.java`, `store/TokenRepository.java`, `account/SessionAuthMechanism.java` (the `sp_2fa` cookie helpers)
- Test: `admin/TwoStepLoginTest.java`, `admin/PasskeyLoginTest.java`

**Interfaces:**
- Produces: `TokenRepository.SECOND_FACTOR = "second_factor"`, `Duration SECOND_FACTOR_TTL = Duration.ofMinutes(5)`, and `Optional<Long> peek(String raw, String purpose)` (unexpired, not consumed). The Task 2 unique index means a new password step replaces the user's earlier unfinished one.
- Produces: `POST /admin/api/login`:
  - for a correct password of a user with `hasFactor`, it answers 200 `SecondStep(boolean totp, boolean passkey)` and sets `sp_2fa`. No `sp_session`, and the account bucket is not reset yet;
  - everyone else gets 204 + `sp_session` as before.
- Produces: `POST /admin/api/login/totp` with `Code(String code)` and the `sp_2fa` cookie:
  - 204 + `sp_session` + cleared `sp_2fa` on success, which consumes the token and calls `limiter.success`;
  - 401 `invalid_code`, counted with `limiter.failure("user:" + id, ip)`; the token stays for a retry;
  - 401 `second_step_expired` with no valid cookie;
  - 429 when blocked.
- Produces: `POST /admin/api/login/passkey-step` with the `JsonObject` credential and `sp_2fa`: `webAuthnSecurity.login(json, ctx)`. It succeeds only when the record's username is the token user's login; then `usePasskey`. The answers match the TOTP step, with 401 `invalid_passkey` instead of `invalid_code`.
- Produces: `POST /admin/api/login/passkey` with the `JsonObject` credential (discoverable, no username): 204 + `sp_session`; 401 `invalid_passkey`, counted with `limiter.ipFailure`. `blockedIp` is checked first. No CAPTCHA, as the spec says.

- [ ] **Step 1: Write the failing tests.**
  - `TwoStepLoginTest.passwordThenTotp`: the password gives 200 `{"totp":true,"passkey":false}` and an `sp_2fa` cookie with `Path=/admin/api/login`, `HttpOnly`, `SameSite=Strict`, `Max-Age=300`, and no `sp_session`. The TOTP step gives 204 and `sp_session`.
  - `TwoStepLoginTest.secondStepTokenIsNotASession`: the `sp_2fa` value sent as `sp_session` to `GET /session` gives 401.
  - `TwoStepLoginTest.expiredSecondStepIs401`: with the `user_token` row's `expires_at` moved into the past, the TOTP step gives 401 `second_step_expired`.
  - `TwoStepLoginTest.wrongCodesHitTheLimiter`: enough wrong codes give 429 on the next TOTP step and on the next password login for that account.
  - `TwoStepLoginTest.userWithoutFactorsStill204`: the existing behaviour.
  - `PasskeyLoginTest.passkeyAloneSignsIn`: a passkey registered via `WebAuthnHardware`, then `GET /q/webauthn/login-options-challenge` with no username, `makeLoginJson`, and `POST /login/passkey` gives 204 + `sp_session`. `last_used_at` is set.
  - `PasskeyLoginTest.anotherUsersPasskeyFailsTheSecondStep`: user A's password step, then B's passkey on `/login/passkey-step`, gives 401 `invalid_passkey`, and A has no session.
  - `PasskeyLoginTest.forgedAssertionIs401`: a login JSON with a tampered signature gives 401, and the IP bucket counts it.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run both classes and `AuthResourceTest`, expect PASS. Commit.** `feat: second sign-in step (TOTP or passkey) and passkey-only sign-in`

### Task 9: Mandatory 2FA

**Files:**
- Create: `account/TwoFactorPolicy.java`, `admin/TwoFactorPolicyResource.java`, `admin/EnrollmentGate.java`
- Modify: `account/SessionIdentityProvider.java`, `store/SessionRepository.java`, `admin/AuthResource.java` (`session`, `setPassword`), `admin/TwoFactorResource.java` (`required`, `last_factor`)
- Test: `admin/MandatoryTwoFactorTest.java`

**Interfaces:**
- Produces: `@ApplicationScoped TwoFactorPolicy`:
  - `boolean required()` reads the `require_2fa` setting (`"true"`);
  - `void set(boolean on, long adminId)` throws `Refused(409, "admin_needs_2fa", null)` when turning it on and `!hasFactor(adminId)`. Turning it on then runs `SessionRepository.deleteAllWithoutSecondFactor()` in the same transaction: one `DELETE` with `NOT EXISTS` over `passkey` and confirmed `totp_factor`.
- Produces: `@Path("/admin/api/security/two-factor") @RolesAllowed("administrator")`: `GET` → `{required}`, `PUT {required}` → the same body. Never republishes.
- Produces: `SessionIdentityProvider` adds attribute `SessionIdentityProvider.ENROLL_REQUIRED` (Boolean) = `policy.required() && !hasFactor(id)`.
- Produces: `EnrollmentGate`, a `@ServerRequestFilter`. With `ENROLL_REQUIRED` true it answers 403 `{"error":"enroll_2fa"}`, except for:
  - `GET /admin/api/session`, `PUT /admin/api/session/locale`, `POST /admin/api/logout`, `GET /admin/api/login-options`;
  - any path under `/admin/api/2fa`.
- Produces: `SessionUser` gains `boolean enrollRequired`.
- Produces: `setPassword`, when `policy.required() && !hasFactor(user)`, answers 200 `{"enroll":true}` with a new `sp_session`. Otherwise it answers 204 as before, with no session: a reset never bypasses an existing factor.
- Produces: `DELETE /2fa/totp` and `DELETE /2fa/passkeys/{id}` answer 409 `last_factor` when `policy.required() && factorCount == 1`.

- [ ] **Step 1: Write the failing tests** in `MandatoryTwoFactorTest`.
  - `adminWithout2faCannotRequireIt`: 409 `admin_needs_2fa`, and the setting is unchanged.
  - `requiringSignsOutUsersWithoutFactors`: an editor without 2FA and an author with a passkey, both logged in. After `PUT {required:true}`, the editor's session is 401, the author's still works, and so does the admin's.
  - `enrollRequiredSessionIsFencedToEnrollment`: the editor logs in again. `GET /session` gives 200 with `enrollRequired: true`, `GET /items` gives 403 `enroll_2fa`, and `POST /2fa/totp` gives 200. After confirming, `GET /items` gives 200.
  - `setPasswordGoesToEnrollment`: with the policy on and an invited user without factors, set-password gives 200 `{"enroll":true}` and an `sp_session`. A user with a factor gets 204 and no cookie.
  - `lastFactorCannotBeRemovedWhileRequired`: one passkey gives 409 `last_factor`. With a second factor it gives 204. With the policy off, the last one can be removed.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class plus `TwoFactorResourceTest`, `AuthResourceTest` and `SessionAuthTest`, expect PASS. Commit.** `feat: mandatory 2FA with an enrollment fence`

### Task 10: Lost device: admin clear and CLI

**Files:**
- Modify: `admin/UsersResource.java`, `account/UserCommand.java`
- Test: `admin/UsersResourceTest.java`, `account/UserCommandTest.java`

**Interfaces:**
- Produces: `DELETE /admin/api/users/{id}/two-factor` (administrator) → 204. It runs `clear(id)` and `sessions.deleteAllFor(id)` in one transaction; 404 for an unknown id.
- Produces: `UserView` gains `boolean twoFactor` (`hasFactor`). The list reads it in the same query, as an `EXISTS` subselect, not per row.
- Produces: CLI `stillpress user reset-2fa <login>` (subcommand of `UserCommand`).
  - It prints `Removed N two-factor methods for <login>. They can sign in with their password.` and exits 0.
  - With the policy on, it appends ` They will be asked to add a new one.`
  - An unknown user prints `No such user: <login>` and exits 1.

- [ ] **Step 1: Write the failing tests.**
  - `UsersResourceTest.adminClearsAUsersFactors`: the user had TOTP and a passkey; afterwards `twoFactor` is false and their session is 401.
  - `UsersResourceTest.listShowsTwoFactor`.
  - `UserCommandTest.cliClearsTheLastAdministrator`: with the policy on and the only administrator enrolled, `reset-2fa admin` prints the policy sentence. Then a password login gives 204 and `enrollRequired: true`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run both classes, expect PASS. Commit.** `feat: clear a user's 2FA from the users screen and the CLI`

### Task 11: Admin UI: second step, passkey sign-in, enrollment redirect

**Files:**
- Create: `webauthn.ts`
- Modify: `api.ts`, `main.tsx` (Gate), `pages/Login.tsx`, `pages/SetPassword.tsx`, `i18n/ru.json`
- Test: `login.test.tsx` (create), `shell.test.tsx`, `setpassword.test.tsx`

**Interfaces:**
- Produces: `webauthn.ts`:
  - `loadWebAuthn(): Promise<WebAuthnClient>` appends `/q/webauthn/webauthn.js` once, like `loadTurnstile`;
  - a local `interface WebAuthnClient { loginClientSteps(u: { username?: string }): Promise<unknown>; registerClientSteps(u: { username: string; displayName: string }): Promise<unknown> }`, constructed as `new window.WebAuthn()`;
  - `passkeyAssertion(): Promise<unknown>` and `passkeyAttestation(login: string, displayName: string): Promise<unknown>`;
  - `passkeysSupported(): boolean` = `!!window.PublicKeyCredential`.
- Produces: `SessionUser` gains `enrollRequired: boolean`. New types `SecondStep { totp: boolean; passkey: boolean }`, `Factors`, `Passkey`, `TotpEnrollment` mirror Tasks 6–9. `UserView` gains `twoFactor: boolean`.
- Produces: the Gate. When `/session` has `enrollRequired` and the path isn't `/two-factor`, it navigates to `/two-factor`. `/two-factor` is open to every role.
- Produces: `Login.tsx`.
  - Step 1 adds a "Sign in with passkey" button when `passkeysSupported()`. It calls `passkeyAssertion()` and then `POST /login/passkey`.
  - A 200 `SecondStep` from `/login` swaps the form for step 2:
    - a code field (`inputMode="numeric"`, `autoComplete="one-time-code"`, `maxLength={6}`) when `totp`;
    - a "Use a passkey" button when `passkey`.
  - Step-2 errors:
    - `invalid_code` → `The code is incorrect.`;
    - `invalid_passkey` → `The passkey was not accepted.`;
    - `second_step_expired` → back to step 1 with `Your sign-in timed out. Enter your password again.`;
    - 429 → the existing text.
  - A cancelled browser prompt (`NotAllowedError`) shows nothing.
- Produces: `SetPassword.tsx`. A 200 `{enroll:true}` navigates to `/two-factor`.

- [ ] **Step 1: Write the failing tests** (`fakeApi` from `testapi.ts`).
  - `login.test.tsx`:
    - `passwordThenCode`: `/login` answers `{totp:true,passkey:false}`; the code field appears; submitting posts `/login/totp` with `{code}` and navigates.
    - `expiredSecondStepReturnsToPassword`.
    - `passkeyButtonHiddenWithoutSupport`: with no `window.PublicKeyCredential`.
  - `shell.test.tsx`: `enrollRequiredGoesToTwoFactor`.
  - `setpassword.test.tsx`: `enrollAfterSetPassword`.
- [ ] **Step 2: Run** `bun test`**, expect FAIL.**
- [ ] **Step 3: Implement.** Mock `webauthn.ts` in the tests; the browser path is checked in Task 13.
- [ ] **Step 4: Run `bun test` and `bunx tsc --noEmit`, expect PASS. Commit.** `feat(admin): second sign-in step, passkey sign-in, enrollment redirect`

### Task 12: Admin UI: two-factor page, mandatory switch, clear 2FA

**Files:**
- Create: `pages/TwoFactor.tsx`
- Modify: `pages/admin.ts`, `main.tsx` (route `/two-factor`), `Shell.tsx` (link `Two-factor` for every role), `pages/Security.tsx`, `pages/Users.tsx`, `i18n/ru.json`
- Test: `twofactor.test.tsx` (create), `security.test.tsx`, `users.test.tsx`

**Interfaces:**
- Consumes: the Task 6–10 endpoints and the Task 11 `webauthn.ts`.
- Produces: `TwoFactor.tsx`, following the existing Shell and form styles (ui-ux-pro-max).
  - With `enrollRequired`, a banner at the top: `This site requires two-factor authentication. Add a passkey or an authenticator app to continue.`
  - **Authenticator app.** When there is none:
    - "Set up" → the QR as `<img src={'data:image/svg+xml;utf8,' + encodeURIComponent(qrSvg)} alt={__('QR code for your authenticator app', 'stillpress')}>`;
    - the secret in a `<code>` for manual entry;
    - a 6-digit field and "Confirm".
    
    When there is one: "Remove".
  - **Passkeys.**
    - A list with name, added and last-used dates, and "Remove" on each.
    - "Add a passkey" with a name field that defaults to the browser's platform.
    - Under it: `A passkey works only on the address it was added on (<host>). After the site moves to its final domain, add your passkeys again.`
  - With exactly one factor: `Add a second method in case you lose this one.`
  - `last_factor` → `This site requires two-factor authentication, so you can't remove your last method.`
  - When `enrollRequired` turns false after an add, a "Continue" link to `/`.
- Produces: `Security.tsx` gets a "Two-factor authentication" section with a checkbox `Require two-factor authentication for every user`. `admin_needs_2fa` → `Add two-factor authentication to your own account first.` with a link to `/two-factor`. The confirmation text says users without 2FA are signed out.
- Produces: `Users.tsx` gets a "2FA" column (`On` / `Off`). "Clear 2FA" on rows with it on, behind the same confirm pattern the screen already uses for destructive actions.

- [ ] **Step 1: Write the failing tests.**
  - `twofactor.test.tsx`:
    - `enrollTotp`: the QR `img` and secret are shown; confirm posts the code; the list refreshes.
    - `lastFactorMessage`.
    - `bannerWhenRequired`.
    - `suggestsSecondFactor`.
  - `security.test.tsx`: `requireNeedsOwn2fa`.
  - `users.test.tsx`: `clearTwoFactor`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `bun test` and `bunx tsc --noEmit` (`strings.test.ts` checks the Russian entries), expect PASS. Commit.** `feat(admin): two-factor page, mandatory 2FA switch, clear a user's 2FA`

### Task 13: Acceptance, docs

**Files:**
- Modify: `README.md`, `docs/superpowers/plans/ROADMAP.md`
- Create: `docs/superpowers/plans/2026-11-15-stillpress-02c-2-results.md`

- [ ] **Step 1: Full build.** `mise exec -- mvn -q spotless:apply verify`: BUILD SUCCESS, including the PostgreSQL tests.
- [ ] **Step 2: Acceptance in the browser.**
  - Dev mode on a throwaway data dir set in `application.properties`' dev profile, not `-Dstillpress.data-dir` (02c-1 lesson). Playwright with Chromium's virtual authenticator, via CDP `WebAuthn.addVirtualAuthenticator` with `hasResidentKey` and `hasUserVerification` true. TOTP codes from `oathtool --totp -b <secret>`, or a `jshell` one-liner on java-otp. Mailpit in Docker for mail, as in 02c-1.
  - Check each of these:
    1. Enroll TOTP and a passkey as admin.
    2. Log out, then password + code.
    3. Log out, then "Sign in with passkey" only.
    4. Turn on mandatory 2FA; an editor without 2FA is signed out, logs in, lands on `/two-factor`, and `/admin/posts` redirects back.
    5. The editor enrolls and continues.
    6. Admin clears the editor's 2FA from Users.
    7. `stillpress user reset-2fa admin` on the packaged jar.
    8. CAPTCHA:
       - an env-locked `turnstile` without keys makes `serve` exit 1 with the message;
       - the ALTCHA widget reads Russian with the admin in Russian;
       - saving Turnstile with a site key from another Cloudflare widget is refused, using the real siteverify and Cloudflare's test keys.
    9. A reset sent while an invite is pending: both links work until one is used. Two resets fired at once mail once.
  - Mode 2: the regenerated nginx include proxies `/q/webauthn/` (render check only, unless nginx is at hand).
- [ ] **Step 3: Docs.**
  - README:
    - 2FA: what mandatory does, `reset-2fa`;
    - passkeys are per host, so add them again after cutover;
    - Mode 2 users must regenerate the nginx include for `/q/webauthn/`;
    - `secrets reset` now drops TOTP;
    - saving Turnstile needs a solved test widget.
  - Roadmap:
    - mark 02c-2 done;
    - mark every bullet under "Carried over from 02c-1 (for 02c-2)" done, with its task number;
    - under "Replicas (PostgreSQL only)", note that the 2FA state is replica-safe: the second-step token and the TOTP replay guard live in the database, and the WebAuthn cookie key comes from the shared keyset override. Only the limiter, already listed, stays per node.
  - Results file in the 02c-1 results format.
- [ ] **Step 4: Commit.** `docs: 02c-2 results; README and roadmap`
