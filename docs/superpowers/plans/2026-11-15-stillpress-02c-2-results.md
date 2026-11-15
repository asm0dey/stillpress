# 02c-2 results: TOTP, passkeys, mandatory 2FA

Plan: `2026-11-15-stillpress-02c-2-two-factor.md`. Commits `83ccc83..0c38ea4` on `main`, plus this docs commit. Three Renovate commits landed in the middle (`f3a8be1`, `f4140a1` db-scheduler 17, `3b66700` jsdom 31).

## Outcome

Done. Users add an authenticator app (TOTP) and passkeys on the Two-factor screen. Login asks for a code or a passkey after the password, and "Sign in with passkey" signs in with a passkey alone. Administrators can make 2FA mandatory. A user without a factor is then fenced to `/two-factor` until they add one. An administrator clears a lost device from Users, and `stillpress user reset-2fa` does it from the server. The 02c-1 carry-over items are closed (see the roadmap).

- `mise exec -- mvn -q spotless:apply verify` on `f80a16f`: BUILD SUCCESS, 736 tests, 0 failures, 0 skipped. The PostgreSQL tests (`Postgres*Test`, Dev Services) run inside the same `verify`. On `0c38ea4` (after the fix): BUILD SUCCESS, 736 tests, 0 failures.
- The acceptance run found one bug (the enrollment redirect, below). It is fixed in `0c38ea4` with a test.

## Acceptance

The packaged jar (`java -jar target/quarkus-app/quarkus-run.jar serve`) ran with `STILLPRESS_DATA_DIR` on a throwaway directory. Dev mode was not used, so no test could touch the site (02c-1's lesson). The browser was Playwright's Chromium with a CDP virtual authenticator (`WebAuthn.addVirtualAuthenticator`: ctap2, internal, resident key, user verification). TOTP codes came from a Python HMAC one-liner, because `oathtool` isn't installed. Mail went to Mailpit in Docker. The site was started empty through the wizard.

1. **Enroll as admin:** Two-factor → Set up showed a QR code and the key. The code was confirmed, then "Add a passkey" (default name "Linux") listed the passkey.
2. **Password + code:** after logout, the password led to "Confirm it is you" (code, or "Use a passkey"). A fresh code signed in to the dashboard.
3. **Passkey only, twice in a row:** logout → "Sign in with passkey" → dashboard, two times in a row within a minute in the same browser. Both sign-ins worked, and no WebAuthn challenge cookie was left behind (only `sp_session` and `csrf-token`). So the challenge cookie is cleared through `ctx.response()`.
4. **Mandatory 2FA:** user `ed` (an editor) was signed in without 2FA. The Security switch asked for confirmation, then turned on, and `ed`'s session answered 401.
   - **First run: failed.** `ed` logged in and landed on `/admin/posts`, which showed "Could not load the list." instead of `/two-factor`. The API fence itself worked (`403 enroll_2fa`). The cause was the UI's `Gate`, which reads `GET /admin/api/setup` first on every navigation. The fence answered that call with 403, and the Gate renders the page on any error except 401. Fixed in `0c38ea4`.
   - **After the fix** (repackaged jar, same data): login → `/admin/two-factor` ("This site requires two-factor authentication…"). `/admin/posts` and `/admin/users` both redirected back to `/admin/two-factor`.
5. **The editor enrolls and continues:** the editor set up the authenticator app, then "Continue" led to the dashboard and `/admin/posts` loaded. This was checked both before and after the fix.
6. **Admin clears the editor's 2FA:** Users → "Clear 2FA" on `ed` asked "Clear two-factor authentication for ed? They are signed out and must set it up again." After that the 2FA column said Off, and `ed`'s session answered 401.
7. **`stillpress user reset-2fa admin`** (packaged jar, beside the running server): it printed "Removed 2 two-factor methods for admin. They can sign in with their password. They will be asked to add a new one." and exited 0. The admin was signed out. The next password login went straight to `/admin/two-factor`, so the CLI gets the last administrator back in with mandatory 2FA on.
8. **CAPTCHA:**
   - `STILLPRESS_CAPTCHA_PROVIDER=turnstile` without keys: `serve` exited 1 with "STILLPRESS_CAPTCHA_PROVIDER is turnstile, but STILLPRESS_TURNSTILE_SITE_KEY and STILLPRESS_TURNSTILE_SECRET must both be set".
   - ALTCHA with the admin in Russian: the reset form showed "Я не робот" and "Защищено ALTCHA".
   - Turnstile on the Security screen, against the real Cloudflare siteverify with Cloudflare's test keys:
     - The test widget solved with site key `1x00000000000000000000AA`, and Save stayed disabled until then.
     - With secret `2x0000000000000000000000000000000AA` (always fails) the save was refused with 400 `turnstile_key_mismatch` ("The site key doesn't match this secret key."), and the provider stayed ALTCHA.
     - With secret `1x0000000000000000000000000000000AA` the pair was saved. The PUT carried `turnstileToken: XXXX.DUMMY.TOKEN.XXXX`.
9. **Reset beside a pending invite:** the checks ran with mandatory 2FA on and each link in a fresh cookie jar.
   - User `inv`: invite first, then a reset. The invite link set the password (200 `{"enroll":true}`), and then the reset link was 410.
   - User `inv2`: the other order. The reset link worked, and then the invite was 410 `invalid_token`.
   - Two concurrent resets for `ed`: both answered 204, and Mailpit got one "Reset your password" mail.
- **Mode 2:** `nginx-config` renders `location ^~ /q/webauthn/` proxied to the upstream with the forwarded headers. This was a render check only, because nginx isn't installed.

## Per task

| Task | Commits | Notes |
|---|---|---|
| 1 CAPTCHA startup checks; reset keeps invite | `963d35a`, `d214dac` | The startup message names the documented `STILLPRESS_TURNSTILE_*` variables, not the plan's. The check is `ServeCommand.check(Captcha, PrintWriter)`. |
| 2 One live token per purpose, atomic throttle, async reset | `609bfa0` | V9 unique `(user_id, purpose)` plus an upsert. The H2 MERGE race gets one retry. |
| 3 Turnstile site key checked on save | `e378bda` | Rebased over the Renovate commits. |
| 4 CAPTCHA UI fixes | `845be45` | Front-end tests run with `bun run test` (vitest). |
| 5 Factor storage | `ad2adb3`, `7cd1b50` | The WebAuthn dependency arrived here, not in T7. A site reset keeps the kept administrator's factors. |
| 6 TOTP enrollment | `56def23`, `175cc99`, `eb33f2f` | Two fix rounds: a concurrent POST could unconfirm a confirmed factor, confirm wasn't atomic or bound to the verified seed, and a MERGE loser gave 500. |
| 7 Passkey registration | `1d549b4`, `69b6737` | The review found a critical bug: a replayed anonymous challenge cookie authenticated as `quarkus-credential`. Fixed with `SessionOnlyAugmentor`. |
| 8 Two-step login, passkey sign-in | `db513b0` | Passkey challenges are single-use, held in memory for `session-timeout` (30 min). |
| 9 Mandatory 2FA | `8abf5a1`, `de5f482` | The fix lets a required user always cancel an unconfirmed TOTP seed. |
| 10 Lost device | `012b62c` | |
| 11 Login UI | `29fe57c` | `Gate` was extracted to `src/Gate.tsx`. |
| 12 Two-factor page, switch, Clear 2FA | `f80a16f` | Native `confirm()` dialogs. |
| 13 Acceptance, docs | `0c38ea4`, this commit | Enrollment fence fix; README and roadmap. |

## Controller rulings

- (Pre-flight) T11 uses `useCaptcha().failed(err)` from T4, not `reset()`.
- (Task 1) The startup message names `STILLPRESS_TURNSTILE_SITE_KEY`/`SECRET`, as documented, not the plan's `STILLPRESS_CAPTCHA_TURNSTILE_*`. Accepted: `ServeCommand.check(Captcha, PrintWriter)` instead of extending `KeysetGuard`.
- (Task 4) The front-end test command is `bun run test`, not bare `bun test`, which has no DOM.
- (Task 5) The WebAuthn extension dependency arrives in T5. `quarkus.webauthn.enabled=false` stayed until T7. `KeysetGuardTest` is accepted instead of `ServeCommand.check`. `SiteReset.reset` keeps the kept administrator's `totp_factor`/`passkey` rows, and `wipe` deletes all of them.
- (Task 7) The extension doesn't bind a challenge to a username, so the plan's `challengeForAnotherLoginIsRefused` was replaced by `passkeyIsStoredForTheSessionUser`. Ownership comes from the session.
- (Task 7) A `SecurityIdentityAugmentor` rejects any non-anonymous identity without `USER_ID`.
- (Task 7) A passkey name's length is counted in UTF-16 units, because H2 `VARCHAR(100)` counts them.
- (Task 8) Passkey login challenges are single-use: an in-memory spent set whose TTL is `quarkus.webauthn.session-timeout` (30 min), not `timeout` (5 min). The challenge cookie is checked against the session timeout.
- (Task 11) `Gate` is extracted for testability, and `login.test.tsx` lives in `src/pages/`.
- (Task 12) Accepted: native `confirm()`, as `Revisions.tsx` already does, plus an extra Cancel button for an unconfirmed TOTP seed.
- (Task 13) The acceptance ran on the packaged jar, not dev mode, so tests and the acceptance site could never share a data directory. The enrollment-fence fix is small and local (one line plus a test), so it went in as a separate `fix:` commit.

## Problems

- **The enrollment redirect never happened** (fixed, `0c38ea4`). The fence (`EnrollmentGate`) answered `GET /admin/api/setup` with 403 `enroll_2fa`. The admin `Gate` calls that endpoint before `/session` and treats only 401 as a redirect, so a user without a factor got every screen rendered with failing API calls instead of `/two-factor`. No test covered it: the back-end tests checked the fence one endpoint at a time, and the front-end tests stub `/setup`. `MandatoryTwoFactorTest.enrollRequiredSessionIsFencedToEnrollment` now asserts 200 for `GET /admin/api/setup`. It failed with 403 before the fix.
- **db-scheduler "Expected one execution to be removed, but removed 0. Indicates a bug" (task `publish-site`) predates db-scheduler 17.** A full `mvn test` was run in temporary worktrees at `f3a8be1` (db-scheduler 16.12.0, before the v17 bump `f4140a1`) and at `f80a16f` (17.1.0). Both log the line once, during `CaptchaEnforcementTest`, right after a publish finished. So it isn't from the upgrade. It looks like a test-cleanup race: `TestStore.clear()` deletes `scheduled_tasks` while a publish execution is completing. The publish-only test classes don't log it at either commit. In the `f3a8be1` run, `PostgresAccountsTest` also failed to boot Quarkus once. That is unrelated (the commit passed CI when it landed) and wasn't followed up.
- **Set-password from a browser that holds an enrollment session** answers 403 `enroll_2fa`, because `POST /admin/api/set-password` isn't open in the fence. This was seen only when two links were opened one after the other in one cookie jar, with mandatory 2FA on. It's a minor, and it's in the hardening backlog.
- **Console 400s:** the first `/admin/api/session` or `/setup` GET after a cookie change shows a 400 before the retry (02c-1's one-GET CSRF retry, already in the backlog). A `GET` from outside the UI rotates the CSRF token, so the UI's next PUT gets 400. That's expected.

## Deferred items

All of these are in the roadmap. The ones the final review won't fix are under "From 02c-2" in the hardening backlog. The ledger's "parked for the final wave" items aren't repeated there:

- Task 2: the retry catches every integrity violation, and the PG comment is wrong; `withoutMailNothingIsSent` can't fail for the right reason; `concurrentResetsMailOnce` could also assert one `user_token` row.
- Task 4: `Security.tsx` keeps a stale `testToken` on a provider toggle; the widget remounts per keystroke; the `Captcha.tsx:64` init cast loses `Headers` instances; `CAPTCHA_LOAD_FAILED` is defined after use.
- Task 9: a test that a confirmed-TOTP-only user gets 409 `last_factor` on `DELETE /totp`; the confirm-vs-delete race.

Replica notes (in the roadmap under "Replicas"):
- The spent passkey-challenge set and the limiter are per node.
- The second-step token and the TOTP replay guard live in the database.
- The WebAuthn cookie key is derived from the shared keyset.
