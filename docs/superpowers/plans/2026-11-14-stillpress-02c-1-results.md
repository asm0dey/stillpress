# 02c-1 results: keys, mail, invites and reset, CAPTCHA, hardening

Plan: `2026-11-14-stillpress-02c-1-keys-mail-captcha.md`. Commits `903c9b2..55cc449` on `main`, plus this docs commit.

## Outcome

Done. Stored secrets are encrypted under Tink keysets, CSRF cookies are signed, every mailed or printed link is built from the `admin_url` setting, SMTP is set up in the admin, imported authors and new users get invites, anyone can reset a password by email, contributors' submissions mail the reviewers, and login, reset, set-password and setup take a `none | turnstile | altcha` CAPTCHA plus a honeypot. The 02c items carried over from 02a and 02b are closed (see the roadmap).

- `mise exec -- mvn -q spotless:apply verify`: BUILD SUCCESS, 663 tests, 0 failures. The PostgreSQL tests (`Postgres*Test`, Dev Services) run inside the same `verify`; the project has no separate `pg` Maven profile.
- The acceptance run found one bug (Turnstile key check, below), fixed in `55cc449` with a test.

## Acceptance

Dev mode through the quarkus-agent MCP, with `-Dstillpress.data-dir` on a throwaway directory, the browser through Playwright, and [Mailpit](https://mailpit.axllent.org/) in Docker as SMTP (`--smtp-auth-accept-any --smtp-auth-allow-insecure`), its mails read through its API.

- **WXR:** `src/test/resources/wxr/sample-blog.xml`, not `blog.xml`: the real export wasn't available (controller ruling). 9 items, 3 comments, 2 authors (`mama`, `papa`), 1 reusable block, imported through the wizard.
- **Admin address and SMTP:** setup had already filled the Admin address with `http://localhost:8080`. It was changed to `http://127.0.0.1:8080` so that a link built from the request host would stand out. SMTP: Mailpit on 1025, security none, a username and password. The test mail arrived ("Тестовое письмо от Блог": the site is Russian).
- **Invite:** `mama` made a contributor and `papa` an editor; "Send invite" on `mama`. The mail ("Приглашение на Блог") linked to `http://127.0.0.1:8080/admin/set-password?token=…`, the admin address, while the admin was using `localhost`. Opening it, setting a password and logging in (by email) worked.
- **Review mail:** `mama` created a post titled `Review me` + newline + `Bcc: injected@example.test` and pressed "Submit for review". Both reviewers, `papa` (editor) and `admin`, got "На проверку на Блог: Review me Bcc: injected@example.test" with a link to `http://127.0.0.1:8080/admin/edit/10`. No `Bcc` header: the line break was stripped.
- **CAPTCHA:**
  - ALTCHA: switched on the Security screen. The login form showed the widget, "Log in" stayed disabled until it was verified, then the login went through (`mama`).
  - Turnstile with a bad secret against the real Cloudflare siteverify: refused, and the provider stayed ALTCHA. The first try said "could not reach Cloudflare" (502). That was the bug: Cloudflare answers `invalid-input-secret` with HTTP 400, and the key check took any non-200 for unreachable. After `55cc449` the same save says "Cloudflare rejected this secret key." (400 `invalid_turnstile_secret`).
  - `stillpress captcha none` (packaged jar): printed "CAPTCHA is now off."; `login-options` then said `"captcha":"none"`.
- **Reset with a forged Host**, curl against the packaged jar's `serve` (prod profile), every request with `Host: evil.example`: `admin` → 204, unknown login → 204, `admin` again → 204. One mail only (5-minute limit), linking to `http://localhost:8080/admin/set-password?token=…`: the admin address, not `evil.example`, valid 60 minutes.
- **Lost keyset**, with the SMTP password stored, packaged jar: after `rm -rf <data>/keys`, `serve` exited 1 with "Stillpress created a new encryption keyset in …/keys, but the database holds secrets encrypted with another one. Restore the previous keyset files (aead.json, prf.json) into …/keys, or run `stillpress secrets reset` to drop the stored secrets." and left no keyset behind. `secrets reset` printed "Deleted 1 stored secrets. Re-enter the SMTP password and CAPTCHA secret in the admin."; the next `serve` started.
- **`STILLPRESS_KEYS_DIR`** on an empty directory: startup failed with "No Tink keysets in …: expected aead.json and prf.json" and nothing was written there.

The site was set up twice. Running a test class in dev mode (for the fix) ran it against the acceptance data directory, because `-Dstillpress.data-dir` on the dev command line overrides the test profile's own data dir, and the test cleanup wiped that site. The Turnstile, `captcha none`, forged-Host and keyset steps ran on a second site set up through the API (setup, start empty, SMTP). Lesson: don't pass `stillpress.data-dir` as a system property to a dev mode that will also run tests.

## Per task

| Task | Commits | Notes |
|---|---|---|
| 1 Tink keys, secrets, signed CSRF | `903c9b2`, `b14858e` | The raw CSRF token goes to the client in an `X-CSRF-TOKEN` response header, its HMAC in the HttpOnly cookie. Review found the lost-keyset guard bypassable (help, version, `secrets` alone, parse errors left a fresh keyset that the next serve accepted); fixed. |
| 2 Token and limiter hardening | `0dbe204` | A new link revokes earlier ones; the token is consumed before the Argon2 hash; limiter pruning amortized. |
| 3 Origins, `admin_url` | `0ee3a1c`, `4640f05` | A real doubled-bracket IPv6 bug found in review. Vert.x rejects host-less HTTP/1.0 before the resource, so the "no Host" branch is defensive. |
| 4 Case-insensitive unique email | `853187d`, `0385e33` | `email_lower` with a unique index; deterministic conflict tests. The update-path 409 is reachable only by a real race (covered at repository level). |
| 4b Shared test password hash | `411258e` | Added at the user's request. Verify 135 s → 123 s. |
| 5 h2c bodies | `447794b` | The h2c bypass was real, and `Connection: close` broke every h2c refusal. |
| 6 SMTP settings | `67e32b5`, `b588d50` | After-commit mail is handed off without blocking the request thread (RED 30.95 s → GREEN 24 ms). |
| 7 Invites and email reset | `86a562b` | `deliver` catches any `RuntimeException`; the async `mail_last_error` leak between tests fixed. |
| 8 Pending-review mail | `7373c75` | CR/LF in a post title reached the subject (header injection); subjects are stripped in `SiteMail.start`. |
| 9 CAPTCHA backend | `2843841`, `5a92dde` | Review: CAPTCHA failures never touched the IP bucket (unlimited outbound siteverify per IP); fixed per the ruling below. |
| 10 CAPTCHA in the admin UI | `7e0f9b4` | Browser check: altcha 3.6.0, login 204. Found and fixed the widget's own challenge fetch rotating the CSRF token (`fetch` → `adminFetch`). |
| 11 Acceptance, docs | `55cc449`, this commit | Turnstile key check fixed; README and roadmap. |

## Controller rulings

- Package-private read accessors used by tests (`Keys.load`, `LoginLimiter.size()`) are allowed; they aren't wrappers or interfaces that exist only for tests.
- Pushing to `origin/main` after commits follows the plan's Global Constraints.
- (Task 1) The raw CSRF token goes to the client in an `X-CSRF-TOKEN` response header; `api.ts` and the tests' `Csrf.csrf()` send it back. Signing is the roadmap fix, and the response can't be read cross-origin.
- (Task 1) The lost-keyset guard runs before every subcommand except `secrets reset`, help and version; otherwise a non-serve command would keep a fresh keyset and the next serve would start with unreadable secrets.
- (Task 1) The implementer verifies that the build creates no keys and bakes none into `target/`.
- (Task 1) Accepted: the raw CSRF token is kept in `localStorage`, with one GET retry on 400; site reset deletes stored secrets; README deferred to Task 11.
- (Task 6) Plan defect: `sendAfterCommit` must not block the committing request thread (a hung SMTP server would stall "create user" for up to 30 s). After commit the send is handed off asynchronously; the blocking send stays only for the test mail.
- (Task 9) The reset checks `blockedIp` before `captcha.verify` (against the brief's "first"), and every reset request still counts once; set-password gets `blockedIp` and `ipFailure` on a CAPTCHA failure; login counts CAPTCHA failures against the IP only. The enumeration-safe rule still holds: 429 and 400 `captcha_failed` are the only exceptions.
- (Task 9) Accepted: `captcha none` runs under the keyset guard, so after a lost keyset the order is `secrets reset` first.
- (Task 10) `startEmpty` is administrator-only, so it needs no CAPTCHA.
- (Task 11) Acceptance imports `sample-blog.xml`, as `blog.xml` isn't available. Every 02c-1 flow works the same on the sample; a re-run on `blog.xml` is needed only if the user wants one.
- (Task 11) SMTP: GreenMail standalone or Mailpit, whatever runs without system packages (Mailpit in Docker). Browser steps through Playwright, a throwaway data dir, forged-Host check with curl, real Cloudflare siteverify with a fake secret, keys check on the packaged jar or dev mode.

## Problems

- **Turnstile key check** (fixed, `55cc449`): Cloudflare answers a bad secret with HTTP 400; it was reported as unreachable (502). The test stub answered 200, so the tests didn't see it. The stub now answers 400 like Cloudflare. The login path was not affected beyond failing closed, which it still does.
- **Dev mode and a forged Host** (Quarkus 3.40): dev mode's host validation answers 400 to any non-localhost `Host`. With `quarkus.http.host-validation.require-localhost=false`, Quinoa forwards `/admin/api` requests with a foreign `Host` to Vite, which answers 403. So the forged-Host step ran against the packaged jar. Production is not affected.
- **A start that fails before the command runs** (here: port 8080 in use) keeps the keyset it generated. The next start then says "does not decrypt" instead of "created a new keyset". It still refuses, so no secret is lost; recorded in the hardening backlog.
- **Console 400s**: the first admin GET after a cookie from another keyset shows a 400 in the browser console before the retry succeeds (Task 1's one-GET retry). Harmless; the retry narrowing is in the backlog.

## Deferred items

All are in the roadmap: 02c-2 items under "Carried over from 02c-1 (for 02c-2)" in Plan 02, plan 04 items under its "Carried over from 02c-1", and the rest under "From 02c-1" in the hardening backlog.

- **02c-2** (it reworks login, reset and set-password):
  - reset timing side channel;
  - concurrent resets can both mail;
  - a reset replaces a pending 7-day invite with a 1-hour link;
  - concurrent token creation can leave two live tokens;
  - an invalid `STILLPRESS_CAPTCHA_PROVIDER` fails on first use, not at startup;
  - an env-locked Turnstile without keys isn't rejected;
  - `login-options` returns a stale `turnstileSiteKey`;
  - the ALTCHA widget is English-only;
  - `challengeFetch` drops the query string and ignores `init`;
  - a failed Turnstile script load is silent;
  - the widget resets on any failed submit;
  - the `loginDisabledUntilToken` test races;
  - `ForgotPassword` treats a failed `login-options` as `mail: true`.
- **02c-2 additions** (final review): the Turnstile site key is never checked on save (verify the pair with a token solved with the new site key and siteverify it with the new secret); fail fast at startup for an env-locked Turnstile without keys and for an invalid `STILLPRESS_CAPTCHA_PROVIDER`; a failed Turnstile script load is silent; a reset replaces a pending invite; the ALTCHA widget is English-only.
- **Plan 04** (comments reuse the CAPTCHA): ALTCHA replay within 300 s; no test for ALTCHA expiry or env-sourced Turnstile keys under the lock.
- **Hardening backlog:**
  - keys: two processes on an empty dir, `Keys.derive` duplication, unset data dir, help paths touching the database, one-row sample, keyset kept after an early startup failure, a root-dependent test;
  - CSRF retry on any 400, and concurrent token bootstrap;
  - a blank `adminUrl` deleting `admin_url`, and a possibly unused TTL;
  - `email_lower` backfill collation, legacy duplicates, the per-author import query, the relative-path test;
  - mail: CR/LF at write time (hardening only: Vert.x `MailEncoder` rejects it, any user can set their own email), `SiteReset` import order, a null subject, per-reviewer settings reads, a package dependency on admin REST, the subject pin, `orTimeout`, two Security-screen nits;
  - h2c: a `Set.of` per request, and 411 on an empty body;
  - test hygiene: boolean assertions, literals, a fixed WireMock port;
  - the dev-mode host validation note.


## Final review

Fixed in the wave after the whole-branch review:

- `stillpress secrets reset` with Turnstile active switches the CAPTCHA off (the deleted secret made every login, reset and set-password fail closed); with an env-locked provider it prints the override warning instead.
- Users screen: "Invite queued for ..." (the send is async), and `mail_not_configured` / `no_email` get their own messages.
- Set-password maps 429 to "Too many attempts...".
- README notes that login, reset and set-password share one per-IP limit.
- Any successful mail send clears `mail_last_error`, not only the test mail.
