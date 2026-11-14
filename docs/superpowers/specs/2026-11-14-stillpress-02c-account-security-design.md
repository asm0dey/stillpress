# Stillpress 02c: account security and mail: design

Agreed 2026-11-14. The main spec (`2026-11-03-wordpress-static-cms-design.md`, §6.2.2) stays the authority. This document settles what §6.2.2 left open and how 02c gets built. Roadmap entry: `docs/superpowers/plans/ROADMAP.md`, "02c account security and mail".

## Intent

Before cutover the admin has to be safe to put on the public internet, and imported authors have to be able to get in. Imported WordPress users arrive with no usable password (§10), so they need invite and reset mail. The login forms face the internet, so they need CAPTCHA and optional or mandatory 2FA. Secrets stored for all of this must not sit in the database in plain text.

## Goals

- Imported authors and new users get an invite email, set a password, and sign in.
- Users reset a forgotten password by email. Without SMTP, the server CLI reset still works.
- Users can add TOTP and passkeys. A passkey alone is a full sign-in.
- An administrator can make 2FA mandatory for the site.
- CAPTCHA (`none | turnstile | altcha`) and an always-on honeypot protect login, reset and setup, as in calit.
- "Pending review" notification mail.
- Secrets at rest are encrypted with Tink.
- The 02c hardening items carried over from 02a and 02b are closed (listed below).

## Non-goals

- Recovery codes.
- A grace period before mandatory 2FA takes effect.
- OIDC or other external login (spec: not in v1).
- Comment-notification mail and comment CAPTCHA. Plan 04 adds them and reuses this plan's mailer and CAPTCHA.
- Items in the roadmap's "Hardening backlog (unscheduled)", including the "Accounts (02a)" line.

## Anti-goals

- A provider widget whose token the server doesn't check (spec).
- A bad CAPTCHA key, a lost 2FA device or broken SMTP locking every administrator out. There is always a CLI way back in.
- Before cutover, invite or reset links pointing at the live WordPress host.
- A second session mechanism. `sp_session` stays the only login cookie.

## Constraints

- The existing auth is our own `sp_session` cookie (`SessionAuthMechanism`, `SessionIdentityProvider`, `session` table) with Argon2id passwords (`Passwords`, password4j), `LoginLimiter` and single-use tokens in `user_token` (`TokenRepository`, `SetPasswordLink`).
- Users are `author` rows with `role` and `password_hash` (V3 migration).
- Storage is H2 by default, with PostgreSQL supported (ADR 0002). Flyway migrations live in `db/migration/common`, and jOOQ is codegen from them.
- Admin front end: TypeScript, Quinoa + Bun, `tsc --noEmit` in the build. Admin i18n: English and Russian.
- Framework first: use Quarkus capabilities before writing our own code.
- Precedent `encryption-tink`: a Tink keyset (not a raw key), per-row AEAD, and keyed HMAC for any value that is looked up.

## Approaches considered (picked in conversation)

| Area | Picked | Rejected, and why |
|---|---|---|
| Passkeys | `quarkus-security-webauthn`, used only through the `WebAuthnSecurity` bean in our own endpoints, which issue `sp_session` on success. Its challenge endpoints and JS client are kept. | The extension's built-in login flow: it sets its own cookie, so there would be two sessions. Raw webauthn4j: the same library minus what Quarkus already wires. |
| Mail | `quarkus-mailer`, with a Vert.x `MailClient` built from SMTP settings stored in the database and rebuilt when they are saved. Bodies are rendered with Qute. | `quarkus.mailer.*` static config: it is read at startup, so admin changes would need a restart. |
| TOTP | `com.eatthepath:java-otp` (RFC 6238), plus a QR code for enrollment. Confirm it is maintained at plan time and pick the QR library then. | Writing it ourselves: small, but it would be security code we own. |
| Encryption | `com.google.crypto.tink:tink`. | Only option (precedent). |
| CAPTCHA | Port calit's `CaptchaVerifier`, `AltchaResource` and `CaptchaProviderConfig`, with `org.altcha:altcha`, and load the widget from a pinned WebJar. | Only option (spec). |

## Decisions

### Sign-in

- The password form stays. A user can register any number of passkeys and at most one TOTP app.
- **"Sign in with passkey"** is a separate button on the login screen. It uses a discoverable credential and requires user verification. On its own it is a complete sign-in and counts as 2FA.
- Password sign-in for a user who has 2FA continues to a second step: TOTP code or passkey.
- Between the password step and the second step, the server holds a short-lived half-authenticated state. It is not an `sp_session` and grants no access. How to hold it is left to the builder.
- CAPTCHA and the honeypot apply to the password step, reset request, set-password and setup. They don't apply to passkey sign-in, because there is nothing to guess. *(my call)*
- `LoginLimiter` also counts failed TOTP codes. *(my call)*

### Mandatory 2FA

- A site setting, off by default. Only administrators can change it.
- An administrator can't turn it on until they have 2FA themselves.
- Turning it on signs out every session of every user who has no 2FA.
- With the setting on, a user without 2FA who signs in with a password lands on an enrollment screen ("add a passkey or authenticator app"). Every other admin route and API refuses them until they enroll.
- Users arriving through an invite or reset link set their password, then go straight to that enrollment screen.

### Lost 2FA device

- No recovery codes.
- An administrator can clear another user's 2FA (all TOTP and passkeys) from the users screen.
- A server CLI command clears any user's 2FA, the last administrator included. It works like the existing CLI password reset.
- Users can register several factors. The enrollment screen suggests adding a second one.
- A user can remove their own factors. With mandatory 2FA on, they can't remove their last one. *(my call)*

### Mail

- SMTP settings in the admin: host, port, TLS mode, username, password, from address. The password is stored encrypted. A "Send test email" button is included.
- Saving SMTP settings rebuilds the `MailClient`. *(my call)*
- **Link host: a stored `admin_url` setting.** *(yours)*
  - Every emailed or printed link uses it: invite, reset, pending review, CLI reset.
  - The setup wizard fills it from its own request's origin. That request is protected by the setup token, so it can be trusted.
  - Administrators can edit it in site settings. At cutover they change it to the final domain, so no separate cutover switch exists.
  - A link is never built from the host of an anonymous request. That would allow reset-link poisoning.
  - An install that predates 02c has no `admin_url`. Fill it from the first authenticated administrator request after upgrade. Until then the CLI prints a path-only link. *(my call)*
- Issuing a new set-password, invite or reset link invalidates the user's earlier unused links of that kind. *(roadmap item)*
- **Reset:** a "Forgot password?" form takes a login or email and always answers the same way, whether or not the account exists. With SMTP it mails a link. Without SMTP it tells the user to ask an administrator, and the CLI reset remains.
- **Invite:** when SMTP is set up, creating a user or pressing "Send invite" on an imported author mails the link. Without SMTP the admin gets the link to copy, as today.
- **Pending review:** when an item moves to pending review, every administrator and editor with an email address gets a mail with a link to the item in the admin. *(my call; WordPress behaviour)*
- Mail is sent in English, or in the recipient's admin language when one is set. *(my call)*
- Sending failures are logged and shown to the admin. They never fail the action that triggered them. *(my call)*

### CAPTCHA

As in spec §6.2.2, ported from calit:

- The provider switch `none | turnstile | altcha` is a site setting.
- When the environment variable is set, it wins. The admin screen shows the provider as locked, and the CLI "switch to `none`" warns that the environment variable overrides it. Whoever can run the CLI can also change the environment, so this is not a lockout. *(my call)*
- ALTCHA: signed challenges with a 5-minute expiry. The HMAC key comes from the keyset (below), so "HMAC key missing" can't happen.
- Turnstile: site key and secret, with the secret stored encrypted.
- Saving a provider verifies its keys first.
- A CLI command switches the provider to `none`.
- An always-on honeypot field.
- The privacy text reflects the active provider. There is no public privacy page until plan 03, so 02c exposes the provider-specific text for plan 03 to place. *(my call)*

### Secrets

- One Tink keyset holds:
  - AEAD for TOTP seeds, the SMTP password and the Turnstile secret;
  - the ALTCHA HMAC key;
  - the `quarkus.rest-csrf.token-signature-key` (roadmap item);
  - a keyed HMAC for anything looked up by value, if needed.
  Separate keys inside the keyset per purpose; the details are left to the builder.
- **Location:** generated on first start into the data dir with owner-only permissions. An environment variable or a mounted secret file overrides it. *(yours)*
- When an override is set, the generated file is never created.
- The README documents that the keyset must be backed up with the database.
- Passkeys are public keys and need no encryption.
- If the keyset is missing but encrypted values exist in the database, Stillpress refuses to start. It does not generate a fresh keyset, because that would silently orphan the stored secrets. The error says to restore the keyset, or to run a CLI command that drops every encrypted value (TOTP enrollments, SMTP password, Turnstile secret) so a fresh keyset can be generated. *(my call)*
- **Single server.** 02c targets one server. Several replicas on PostgreSQL stay in the unscheduled backlog. They would need a shared keyset override, settings-change notification across nodes, and shared limiter and half-auth state. Add these to the roadmap's "Replicas (PostgreSQL only)" list. *(my call)*

### Hardening carried over (all in 02c)

From the roadmap's 02a and 02b carry-over sections:

1. CSRF: set `quarkus.rest-csrf.token-signature-key` from the keyset.
2. h2c bodies without `Content-Length` may bypass `BodyLimitFilter`. Check with `curl --http2-prior-knowledge`, and fix it or set `quarkus.http.http2=false`.
3. Set-password hashes before consuming the token. Consume or check the token first.
4. Issuing a set-password link leaves earlier links valid (covered under Mail).
5. `LoginLimiter.prune()` walks both maps on every call under one lock.
6. Setup returns 500 without a `Host` header. IPv6 brackets in `home_url` are unchecked.
7. Set-password links are built from the imported site URL (covered under Mail).
8. No unique index on `lower(email)`, and one email error text is misleading.
9. An over-long password counts against the account's login bucket.
10. "Pending review" notifications (covered under Mail).

## Split

Two plans, in this order. Each gets its own plan file and results file, like 02a and 02b.

- **02c-1: Tink, mail, invites and reset, CAPTCHA, hardening.** It comes first because imported authors need it before cutover.
- **02c-2: TOTP, passkeys, mandatory 2FA, 2FA admin and CLI reset.**

## Left to the builder

- Table and column design for TOTP secrets, passkey credentials and SMTP/CAPTCHA settings, and whether secrets use `setting` rows or their own table.
- How the half-authenticated state between the password step and the second step is held (a short-lived `user_token` purpose is one option).
- Keyset file name, environment variable name, and how separate purposes map to keys in the keyset.
- Mail template wording and layout.
- Admin screen layout for the security settings, a user's own 2FA page and the enrollment screen, following the existing admin shell and ui-ux-pro-max design.
- WebAuthn relying-party ID and origin handling, across the pre-cutover host and the final domain. Passkeys registered on one host don't work on another, and the enrollment screen should say so.
- Test strategy, following the project's existing practice (dev-mode test tools, jetCheck where property tests fit).
