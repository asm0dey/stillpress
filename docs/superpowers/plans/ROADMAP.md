# Stillpress roadmap: plans 01-05

This file says which plan builds which part of the design spec. The spec (`docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md`) is the authority; this roadmap only maps spec features to plans and keeps a list of items deferred while building plan 01, so none get lost. Each plan gets its own detailed plan file when it is started; until then the bullets here are scope, not tasks. Every spec feature is listed under exactly one plan. Section numbers are spec sections.

Migration order from the spec (§10): the blog site goes first (plans 01-04), then `it` and `newpodcast` once podcasting and code highlighting exist (plan 05).

## Plan 01: Core pipeline (in progress)

- Project skeleton, mise tooling, H2-by-default storage with PostgreSQL support, Flyway and jOOQ (§6.1, ADR 0002).
- Gutenberg block parser and Java block renderer: static blocks pass through, YouTube click-to-load facade, classic-content cleanup.
- One-shot WXR import (streaming) with media download, URL rewriting, authors, terms, comments and import report.
- Permalinks, redirect map, and "never publish drafts, private or password-protected items".
- Minimal built-in "plain" layout, archives, pagination, RSS feeds, comment feeds with the source GUIDs.
- Atomic static publish into `output/current` and the Stillpress-side serving every mode needs: files, redirect map and 404 (this is Mode 1, and the fallback target in Mode 2).
- `verify-urls` crawler (cutover check 1 of §12.1).

## Plan 02: Admin & editing

Split into three plans (agreed 2026-11-04):

- **02a admin foundation** (done; `2026-11-04-stillpress-02a-admin-foundation.md`, results in `2026-11-04-stillpress-02a-results.md`): Mode 2 nginx include, setup wizard with token and WXR upload, admin shell (Quinoa + Bun), `/wp-admin` redirects, password login with rate limiting, users and roles, CLI password reset, server-side import-once guard.
- **02b editing and publishing**: editor, WP REST slice, classic block, reusable blocks, media library, statuses and scheduling, incremental and background republish, preview, revisions, admin i18n.
  - Scheduled publishing: choose with the user at plan time between in-process db-scheduler (the precedent in four projects) and quarkus-scheduler.
- **02c account security and mail**: TOTP, passkeys, mandatory 2FA, CAPTCHA, SMTP with reset, invites and notifications.
  - Secrets stored at rest (TOTP seeds, SMTP password, Turnstile secret, ALTCHA HMAC key) are encrypted with Google Tink: a keyset, per-row AEAD, and a keyed HMAC for anything looked up by value (precedent `encryption-tink`).

- Serving Mode 2, the default: one-time generated nginx include (static root at `output/current`, `try_files $uri $uri/ @stillpress` fallback, proxied `/admin`, login, API, preview and legacy query URLs) plus an example nginx setup; the acceptance run goes through nginx (§6.2 item 9, ADR 0004). Mode 1 (Stillpress serves everything) ships in plan 01 and stays supported.
- Setup wizard at `/admin` with one-time setup token; wizard screens for Start empty and WXR upload (§6.4).
- Admin on each site's own domain at `/admin`; `/wp-admin` and `/wp-login.php` redirect there (§6.2 item 10).
- Admin front end: Quinoa + Bun (pinned equal to `mise.toml`, with a build check), ui-ux-pro-max design (§6.1, §6.2 item 3).
- Admin REST backend plus the WordPress REST slice the editor needs (`wp/v2/blocks`, `types`, `themes`, `users`, `oembed/1.0/proxy`, media), including `POST` + `X-HTTP-Method-Override`, and logging of unknown `/wp-json` calls (§6.2 item 2).
- Embedded Gutenberg editor for posts and pages, saving dirty `core-data` entities with the post (§6.2 item 3, ADR 0001).
- Classic block (TinyMCE) for classic items, with "Convert to blocks" on demand, never automatic (§10).
- Reusable blocks: separate pattern editor screen bound to the `wp_block` entity (§6.2 item 3).
- Media library and upload (§6.2 item 3).
- Password login with Argon2id/bcrypt and per-account and per-IP rate limiting (§6.2.2).
- Optional TOTP and passkeys, with an admin switch to make 2FA mandatory (§6.2.2).
- SMTP configured in admin for resets, invitations and "pending review" mail; server CLI reset without SMTP (§6.2.2).
- CAPTCHA on login, reset and setup: `none | turnstile | altcha` switch, ALTCHA challenge endpoint, Turnstile siteverify, honeypot, key check on save, CLI switch to `none`, privacy-text hook (§6.2.2).
- Multiple users with roles (administrator, editor, author, contributor); imported authors become accounts that set passwords by invite or reset (§6.2.2, §10).
- Item statuses (draft, pending review, scheduled, published, private) and scheduled publishing (§6.2.1, §11).
- Publish/Update with incremental republish and dependency tracking (item, listings, archives, feeds, items using an edited reusable block) (§6.2 item 8, §6.2.1, §12).
  - Recommended approach (agreed 2026-11-03). A new post shifts every pagination page of every listing it lands in: home, category, tag, author and date archives. At 1,000 pages × 10 posts that is ~1,000–1,500 files per publish, so:
    - cache one list-entry fragment per post (title, link, date, excerpt as ready HTML), rebuilt only when that post changes;
    - assemble each pagination page by concatenating cached fragments into the layout: no block rendering or per-post queries;
    - build the new release by hard-linking every unchanged file from the previous one and writing only changed files, so the atomic swap stays cheap on large sites.
  - Rejected: numbering pages from the oldest post (breaks WordPress `/page/N/` URL parity); rebuilding only the first few pages eagerly (readers would see mismatched pages, breaking the always-complete release); client-side pagination from a JSON index (no JavaScript means no page; bad for SEO and old links).
- Background full-site republish with progress in the admin (§6.2.1).
- Preview rendered on the fly for logged-in users, never written to output (§6.2.1).
- Revisions and autosave with compare, restore and 25-per-object retention; covers items and reusable blocks now, and templates and style overrides when plan 03 creates them (§6.2.6).
- Admin UI translation, per-user language, English and Russian (§6.2.4).
- Server-side enforcement of "a site is imported at most once" inside the wizard (§3, §6.2 item 4).

### Carried over from plan 01

- The importer's one-shot check only looks at whether items exist; the wizard must enforce "import once" more completely (for example also for media, comments and settings).

### Carried over from 02a (acceptance run)

- 02b: an author who opens `/admin/users` directly sees "Could not load the users." and the Add user form; hide screens the role cannot use.
- 02c: set-password links are built from the imported site URL, so before cutover they point at the live WordPress host; invites and email reset reuse the same builder and should use the admin's own host until cutover.

### Carried over from 02a (code reviews)

- 02b: in Mode 2 nginx's `^~ /admin` keeps `client_max_body_size 1g`, so nginx spools large anonymous bodies to disk before Stillpress answers 413; split it per location (1g only for `/admin/api/import` and the media upload).
- 02b: new upload endpoints under `/admin/api/` must be added to `BodyLimitFilter`'s exclusions or they get 413.
- 02b: admin shell: the route gate's `ready` stays true across navigation; the dashboard's 401 goes to `/login` without `next`; no cancellation of stale responses; field errors are not announced (no focus move, no `aria-live`).
- 02b: users screen: create lacks trim and length checks (a 61-character login gives 500); a duplicate-login race gives 500, not 409; duplicate emails are accepted; setup's upgrade path ignores the typed email and display name.
- 02b: the import screen stops polling while a CLI import runs in another process (reload needed).
- 02b: `Import.tsx` uses `status!` where a local alias would narrow; `catch` blocks cast `e as ApiError` instead of a type guard.
- 02c: possible HTTP/2 cleartext (h2c, prior knowledge) bodies without `Content-Length` bypass `BodyLimitFilter`; check with `curl --http2-prior-knowledge`, or set `quarkus.http.http2=false`.
- 02c: the `quarkus-rest-csrf` token cookie is unsigned double-submit; a same-site sibling subdomain could plant a token cookie and submit a matching multipart form to `/admin/api/import`. Set `quarkus.rest-csrf.token-signature-key` from a persisted secret (the Tink-managed secrets work).
- 02c: set-password hashes the new password before consuming the token, so an invalid token still costs an Argon2 hash on an anonymous endpoint.
- 02c: issuing a set-password link leaves the user's earlier links valid.
- 02c: `LoginLimiter.prune()` walks both maps on every call under one lock.
- 02c: setup returns 500 on a request without a `Host` header; IPv6 brackets in `home_url` are unchecked.
- Docs: behind a CDN or load balancer, overwriting `X-Forwarded-For` with `$remote_addr` makes every client share one address; the README needs a line when that setup is supported.
- Docs: `open_file_cache` at http level delays the release swap until `open_file_cache_valid` expires.

## Plan 03: Themes & Site Editor

- Style generator for layout support, global styles and block-supports CSS, shared by the editor canvas and the static site (§6.2 item 6).
- Theme engine: template hierarchy with WordPress fallback rules (§6.3).
- Template parts overridable individually (§6.3).
- Child themes (§6.3).
- Menus with theme-declared locations, plus the menus admin screen (§6.3, §6.2 item 3).
- Widget areas, plus the widgets admin screen (§6.3, §6.2 item 3).
- Theme settings (customizer-equivalent: colours, fonts, logo, header image, layout) edited in admin (§6.3).
- `theme.json`-equivalent for palette, typography, layout widths and block style variations (§6.3).
- Block themes as the primary model, rendered by the Java block renderer; classic-style Qute themes as the secondary model (§6.3).
- Dynamic block renderers that block themes need (query loop, navigation, site title, post content and similar) (§6.2 item 5; assignment is a judgement call).
- Site Editor: global styles, templates and template parts, stored as per-site style overrides (§6.3, §10).
- Full-site atomic republish on theme or settings change (§6.2.1; the trigger is wired here, the mechanism is plan 02).
- Rebuilt **saga** block theme and one neutral default theme (§6.3, §10).
- One language per site driving theme strings, dates, pagination labels, `<html lang>`, feeds and search; theme translation files, Russian included (§6.2.4).

### Carried over from plan 01

- Dates on pages and in templates are currently raw ISO strings; they need localised, theme-controlled formatting.

## Plan 04: Blog plugins & blog cutover

- Per-site plugin enablement, and plain fallback rendering for blocks of a disabled plugin (§6 goal 6, §6.2 item 5, §7).
- SEO plugin: title, meta, Open Graph/Twitter, canonical, JSON-LD (§7).
- Sitemap and robots (§7, §6.2 item 8).
- Galleries: core gallery (old and nested variants), `[gallery]` shortcode, and Jetpack Tiled Gallery with layout computed at publish time (§7, §8).
- 360° panorama block (§7, §8).
- Video blocks (§8; assignment is a judgement call).
- Embeds plugin: YouTube facade settings plus the further oEmbed providers the imported content actually uses (§7, §11, §12).
- Header/footer snippets (§7).
- Comments plugin: threaded display of imported comments, native submission with CAPTCHA and honeypot, moderation queue, WordPress-style hold and auto-approve rules, notifications, close after N days, approval republishes the page and comment feed (§6.2.3, §7).
- Client-side static search index generated at publish (§11).
- Image pipeline: keep originals, responsive sizes, WebP/AVIF with fallback, as a publish step (§7, §12).
- REST import with an application password, covering settings, menus, widgets, global styles, templates, navigation and custom CSS (§6.2 item 4, §10; assignment is a judgement call).
- Export helper WordPress plugin, shipped as a zip with each release, and the importer for its bundle (§6.2.5, §10).
- Import report completeness for the blog's content: unknown blocks, shortcodes, missing media, password-protected items (§6.2 item 4, §4).
- Distribution: multi-arch JVM image on GHCR and example `compose.yaml` with optional PostgreSQL and nginx (§6.2.5; assignment is a judgement call).
- Cutover gate checks 2-5 for the blog: screenshot diff, counts match, feed GUIDs match, hands-on session (§12.1).

### Carried over from plan 01

- Legacy `core-embed/youtube` blocks are not recognised yet; they should render as the YouTube facade.
- The YouTube link matcher does not anchor on the host name, so look-alike URLs could match.
- The embed facade drops aspect-ratio and alignment classes from the original block.
- Automatic paragraphing only protects captions; galleries, embeds, audio and video shortcodes need the same treatment.
- Caption shortcode parsing is narrower than WordPress's: unquoted attributes, uppercase keys, the `wp_caption` alias and odd class values are not handled.
- A caption width written like `300px` makes the figure disappear (WordPress reads it as 300).
- Raw non-breaking spaces are not treated as whitespace by the paragraphing step.
- Feeds lack `<generator>`, `<comments>` and `<slash:comments>`; decide whether the feed-match check needs them.
- Author slug generation uses an approximate accent table; it must match WordPress's exactly, because author archive URLs depend on it.
- Import cannot yet render the full fixture post end to end in a single test; add one before the real blog import.

## Plan 05: Podcasting & it/newpodcast cutover

- Podcasting plugin: episodes, series, speakers (§7).
- Podcast player block (§7).
- Podcast RSS feed with iTunes and Podcasting 2.0 tags at the unchanged feed URL; `/feed/podcast/` redirect to the per-series feed keeps working (§7, §8.1).
- Episode GUIDs imported and emitted verbatim (`?post_type=podcast&p=<id>`) (§8.1).
- `/podcast-download/<id>/<file>` URLs keep resolving (§8.1).
- Download counting: dedupe per IP + user agent + episode + day, bot filtering, 302 to the static file, daily hash only (§7).
- Download statistics in admin, plus import of historic Seriously Simple Stats data from the export helper (§7).
- Podcast channel and series settings imported from the export helper bundle, with the live feed as cross-check and fallback (§11).
- Code syntax highlighting (Enlighter replacement) (§7).
- Analytics snippet (Umami-style) (§7).
- Rebuilt **shift** and **penscratch-2** themes (§6.3, §10; assignment is a judgement call).
- Import and cutover of `it` and `newpodcast`, including WXR import because their REST API blocked the scan (§8).
- Podcast-feed parity checks in the cutover gate: enclosure URLs and GUIDs (§12.1).

### Carried over from plan 01

- None yet.

## Hardening backlog (unscheduled)

Robustness items from plan 01 that fit no feature plan. Pull them in when touching the area.

- Block parser: an empty closing string is skipped where WordPress keeps it; leading text is only tracked at depth 0; closing-tag attributes are parsed needlessly; tests are missing for leading freeform text, multi-level implicit close, nested void blocks, three or more nesting levels and byte-exact round trip.
- Block parser: headers that never complete are rescanned repeatedly (quadratic on hostile input); one test should pin the output order instead of ignoring it.
- Block renderer: a duplicate block name silently replaces the earlier one; a malformed number silently becomes 0.
- WXR reader: header elements that appear after the first author are not emitted.
- Importer: a malformed or percent-encoded `%2F` / non-UTF-8 permalink aborts the whole import instead of being reported.
- Importer: media is held as whole-file byte arrays (the interface is fixed); a single multi-gigabyte import transaction on H2 is untested; a failed import rolls back the database but leaves downloaded media orphaned.
- Importer: uploads under a home path that differs from the site URL are reported missing; host matching is case-sensitive; a failed folded original leaves a media row with no path that blocks retry; reusable-block references are matched by source id only.
- Importer tests: no test for an attachment whose parent is a draft; the media count in the report is not asserted.
- CLI: exit codes do not distinguish failure kinds.
- CLI: `--help` is not wired; `nginx-config --help` prints "Unknown option" before the usage.
- Build: the schema test covers only the `setting` table; some dependency versions are inline instead of properties; nothing enforces that runtime jOOQ equals the code-generation jOOQ; the dialect test checks the Quarkus mapping, not the property expansion.
- Licence: `LICENSE` was reflowed and carries no explicit "or later" statement.
- Accounts (02a): `findByLoginOrEmail` lower-cases in Java and SQL separately (non-ASCII emails may disagree); `setRoleKeepingAdministrator` locks without `ORDER BY` and has no concurrency test; `PasswordsTest` lacks the 11-character case; only `AuthResourceTest` resets the shared login limiter; no rollback test for the set-password transaction; no test that no setup token is printed when an administrator already exists.
- Import (02a): an old job's `finally` can briefly race a fast retry's `start()`; the upload file stays in `<data>/import` if the thread fails to start; no test for a publish failing after content was written; the admin media cap rejects a negative value only when an import starts and its test proves only a cap of 0.
- nginx (02a): the `root` path is unquoted in the template; HEAD is untested through nginx; small Mode 1/Mode 2 differences (POST to a static file gives 405, `%2F` is decoded by nginx, a bare `?p` is served by nginx).
- Tests (02a): `PostgresAccountsTest` waits on futures without a timeout; `postgres:18` is written both in the test and in the codegen config; every `@QuarkusTest` augmentation runs `bun install` and a Vite build; the root-skip in `ReleaseSwitcherTest` checks `user.name`, not the uid.
- Admin API: `ImportResource` builds `Map.of("error", e.getMessage())`, which throws (500) when the exception has no message. `api.ts`: the `CSRF_MESSAGE` doc comment sits between `ApiError`'s comment and the interface.

## Not in scope

From the spec's non-goals and decisions:

- Running PHP, WordPress themes or WordPress plugins (§3).
- A third-party plugin ecosystem or public plugin API; theme- or site-supplied custom blocks (§3, §11).
- Stable `/wp-content/uploads/...` asset paths (§3).
- Two-way sync with a running WordPress and second imports; a bad import is redone by wiping the data directory (§3, §6.2 item 4).
- AMP, backups, ManageWP, object caches, nginx cache purging, image-regeneration tools (§3, §7).
- Multilingual sites (one item in several languages) (§6.2.4).
- Multiple sites in one install (§6.4, ADR 0003).
- External login (OIDC) in v1 (§6.2.2).
- Password-protected posts in v1; import flags them in the report (§11).
- GraalVM native image (§6.2.5).
- Importing WordPress revisions (§6.2.6).
- Carrying over WordPress password hashes (§6.2.2).

## Open questions

- Imported site configuration touches menus, widgets, global styles and templates (plan 03 objects) but the REST/helper importers sit in plan 04. Is that the right split, or should configuration import move into plan 03?
- Spec §6.2 item 4 says importers run from the setup wizard; the wizard arrives in plan 02 with WXR only, and REST import arrives in plan 04. Acceptable?
- Shift and penscratch-2 are assigned to plan 05 because their sites move last; the spec says "ship rebuilt saga, shift, penscratch-2" without a timing. Confirm.
- Whether a classic-style theme (Qute) is needed for Shift or Penscratch 2 is left to the builder (§6.3); the answer decides how much of plan 03 serves plan 05.
- The spec lists the comment moderation queue under comments (§6.2.3) but places the admin screens only generally (§6.2 item 3); this roadmap puts the moderation queue in plan 04 with the comments plugin.
