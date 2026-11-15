# Stillpress

A self-hostable CMS on Quarkus that replaces WordPress for blogs. Authors write in the Gutenberg block editor inside the app; readers only ever get pre-rendered static files. Existing WordPress sites come in through a one-shot import and keep their URLs and their look.

One install runs one site. Several sites means several installs.

> **Status: early development.** Plan 01 (the core pipeline: import → store → render → publish → serve) is done and has been run against a real blog: every post, page, archive, pagination page and feed URL matches the live WordPress site ([results](docs/superpowers/plans/2026-11-03-stillpress-01-results.md)). Plan 02a (the admin foundation: setup wizard, login, users, wizard import, nginx Mode 2) is done and has been run against the same blog through nginx ([results](docs/superpowers/plans/2026-11-04-stillpress-02a-results.md)). There is no theme yet; pages use a minimal built-in "plain" layout. Plan 02b (editing and publishing: the block editor, scheduling, incremental publishing, revisions, an English and Russian admin) is done and has been run on the same blog, plus one built from scratch ([results](docs/superpowers/plans/2026-11-04-stillpress-02b-results.md)). Plan 02c-1 (encrypted secrets, SMTP mail, invites and email reset, "pending review" mail, CAPTCHA) is done ([results](docs/superpowers/plans/2026-11-14-stillpress-02c-1-results.md)); 2FA and passkeys come in 02c-2. The rest is in the [roadmap](docs/superpowers/plans/ROADMAP.md).

## What works today

- Parsing WordPress block markup, as a port of `WP_Block_Parser`.
- Rendering blocks: static blocks pass through byte for byte, reusable blocks are resolved, and YouTube embeds become click-to-load facades.
- Classic (pre-Gutenberg) content: ports of `wpautop()`, `shortcode_unautop()` and the `[caption]` shortcode.
- Streaming the WXR export reader: multi-GB exports never sit in memory.
- A one-shot importer: posts, pages, attachments, terms, authors, comments and reusable blocks.
  - Media is downloaded into `data/media/`, and its URLs are rewritten in HTML and block JSON, Jetpack Photon (`i0.wp.com`) links included.
  - A redirect map is built for `?p=`, `?page_id=`, `?attachment_id=` and attachment pages.
- The publisher: every published post and page, home, category, tag, author and date archives (paginated), and RSS feeds, rendered into a fresh release that goes live atomically.
- Background publishing: every change is queued and published by a [db-scheduler](https://github.com/kagkarlsson/db-scheduler) task in the server, which re-renders only what the queue touched. One publish runs at a time across the server, the CLI and replicas. Scheduled posts go live at their time. The dashboard shows the publish state and progress (`GET /admin/api/publish`), and administrators can start a full republish (`POST /admin/api/publish`). A failed publish keeps its queue and retries a minute later. `stillpress.publish.sweep-interval` (default `1m`) is how often a sweep re-requests a publish for queued changes whose request was lost. `stillpress.publish.polling-interval` (default `10s`) is how often the scheduler looks for due tasks, so a scheduled post goes live up to that long after its time.
- Items API for the editor (`/admin/api/items`, any logged-in user; WordPress's role capabilities decide what each role may do): list with per-status counts, create, save, trash and restore posts and pages. A save carries `expectedModifiedAt` and gets 409 when someone saved in between. Publishing assigns the permalink from `permalink_structure` (pages: parent slugs), a slug or date change on a published item leaves a 301 from the old path, and a new item gets the next WordPress public id with its `?p=` shortlink. Every save is a revision (the newest 25 are kept, setting `revisions_keep`), with per-user autosave (`PUT /admin/api/items/{id}/autosave`) and restore (`POST /admin/api/items/{id}/revisions/{rid}/restore`). `GET /admin/preview/{id}` renders an item, from your newer autosave, without publishing it.
- Reusable blocks API (`/admin/api/blocks`): list (with who owns each block, how many items use it and whether you may edit it; `?content=true` adds each block's content), create, save with `expectedModifiedAt` (409 on a conflict), per-user autosave, revisions and restore. Authors and up create blocks and edit their own; editors and administrators edit any; contributors only insert them. A new block gets the next WordPress public id, the `ref` in `<!-- wp:block {"ref":N} /-->`. Saving a block republishes every published item that shows it, through other blocks too; the PUT answer's `republished` says how many.
- Script-capable HTML (WordPress's `unfiltered_html`): only administrators and editors may save it. An author's or contributor's save or autosave of an item or reusable block whose title, body or excerpt holds any of the following is refused with 400 `{"error":"unsafe_html","field":…,"violations":[…]}`; nothing is stripped silently. Each violation is one line of the list:
  - `<script>`, `<style>`, `<object>`, `<embed>`, `<base>`, `<form>`, `<meta>` or `<link>`; `<noscript>` (browsers and the checker read its content differently); SVG `<animate>`, `<set>` and `<animateTransform>`, which can set a link to a script URL;
  - attributes on `<html>`, `<head>` or `<body>`, which browsers move onto the page's own elements;
  - an `on*` or `srcdoc` attribute, a `javascript:`/`vbscript:`/`data:` URL (`data:image/` on `img src` is fine), script in a `style` attribute, or an `<iframe>` from anywhere but YouTube or Vimeo;
  - "unclosed markup at the end": a tag, attribute, comment or element such as `<textarea>` left open, which would swallow what the page shows after it;
  - "stray </x> end tag": an end tag with nothing to close, which would close part of the page around the content (misnested `<b><i>…</b></i>` is fine);
  - "block comment inside HTML": a `<!-- wp:… -->` block delimiter inside a tag or attribute, where it is not a comment. Imported content is not re-checked, so an author editing an imported post with a script must remove it before saving.
- Media library API (`/admin/api/media`): `POST` a multipart `file` (authors and up; 201 with the same view as `GET /admin/api/media/{id}`), `GET ?type=image|audio|video|file&search=&page=` to list, `PUT /{id}` with `{title, alt}`. Allowed: jpg jpeg png gif webp avif mp3 m4a ogg wav mp4 webm mov pdf zip (anything else is 400 `type_not_allowed`; svg and html would run as script on the site). Files land in `media/YYYY/MM/` (site time zone) under a slugged name, `-2`, `-3` on a clash, and are published at `/media/YYYY/MM/<name>`. The generated nginx include allows 1 GB bodies only for `/admin/api/import` and `/admin/api/media`; the rest of `/admin` gets 1 MB.
- Site settings (`GET`/`PUT /admin/api/settings`, administrators; the Settings screen): title, tagline, language, site address, admin address (`adminUrl`, the origin every mailed or printed link starts with; `""` clears it), time zone, posts per page and per feed (1 to 100), permalink structure, category and tag bases, default category and default comment status. A bad field is 400 `{"error":"invalid","field"}`. Changing the title, tagline, language, address, a count or a base republishes the whole site; changing a base also leaves a 301 from each old archive address (terms included) to the new one. A new permalink structure applies only to posts published afterwards. A site started empty works as a blog: the first published post without a category goes into the default category ("Uncategorized", "Без рубрики" on a Russian site), created then and not at setup, and archive pages show an automatic 55-word excerpt for posts without one.
- Outgoing mail (`GET`/`PUT /admin/api/mail`, administrators; the Email section of the Security screen): SMTP host, port, security `none|starttls|tls`, username, password and from address. The password is stored encrypted under the keyset and never sent back (`passwordSet` says whether one is stored); a `PUT` with `password: null` keeps it and `""` deletes it, and `host: ""` turns mail off and clears every SMTP setting. `POST /admin/api/mail/test` mails the signed-in administrator in their admin language: 204, 400 `no_email`, 409 `mail_not_configured` or 502 `{"error":"mail_failed","message"}`. Mail sent by an action never fails it: a failure is logged and kept in `lastError` until a test mail goes through.
- CAPTCHA (`GET`/`PUT /admin/api/captcha`, administrators; the CAPTCHA section of the Security screen): `provider` `none|turnstile|altcha`, `turnstileSiteKey` and `turnstileSecret` (stored encrypted, never sent back; `secretSet` says whether one is stored, `null` keeps it). Saving Turnstile checks the secret with Cloudflare first: 400 `invalid_turnstile_secret`, or 502 `turnstile_unreachable`. With `STILLPRESS_CAPTCHA_PROVIDER` set, `locked` is true and a `PUT` is 409 `captcha_locked`. The anonymous side: `GET /admin/api/login-options` returns `mail` (whether SMTP is set up), `captcha` (the provider) and `turnstileSiteKey`; `GET /admin/api/altcha/challenge` issues a signed challenge valid 5 minutes; login, `POST /admin/api/password-reset`, set-password and setup take the answer in `captcha` and the honeypot in `website`, and a wrong one is 400 `captcha_failed` and counts against the per-IP limit.
- Categories and tags (`/admin/api/terms`; the Categories and Tags screens): `GET ?taxonomy=category|post_tag` lists them with `description` and the number of published posts (`count`), `POST` creates one (authors and up, also from the editor). Editors and administrators `PUT /{id}` `{name, slug, parentId, description}` (slug unique per taxonomy, else 409 `slug_taken`; a parent cycle is 400 `field: "parentId"`) and `DELETE /{id}` (the default category is 409 `default_category`). A new slug or parent moves the archive of the term and its subcategories, with a 301 from each old address; deleting moves subcategories up to the deleted one's parent, and a post left without a category gets the default one. Archives and the pages of affected posts are republished.
- Redirects (`/admin/api/redirects`, administrators; the Redirects screen): `GET ?search=&page=` → `{items:[{from,to,status}],total}`, `POST {from, to, status: 301|302}`, `DELETE ?from=`. `from` is a decoded path starting with `/` or a query such as `?p=12`; `to` is a site path or an absolute http(s) URL. A `from` at a published item's address is 409 `shadows_item`. Stillpress serves redirects itself, so no publish is needed.
- User profiles: `PUT /admin/api/users/{id}` `{displayName, email}`; administrators edit anyone, other users only themselves. Bad input is 400 with `field`, an email another user has is 409 `email_taken`. A new display name republishes the author archive and the user's published items; the `/author/<slug>/` address never changes.
- Editor lookups: `GET /admin/api/media/{id}`, and the embed preview `GET /admin/api/oembed?url=`, which asks only YouTube and Vimeo (https, 5 s timeout, no redirects) and answers 404 `oembed_invalid_url` for anything else without fetching it.
- Serving the live release with WordPress-compatible redirects as the fallback.
- A URL parity checker that crawls the live site's sitemaps and feeds and compares every URL against Stillpress.
- The admin at `/admin`, written in TypeScript (React, built by Bun through Quinoa):
  - The editor (`/admin/edit/{id}`): WordPress's Gutenberg block editor (`@wordpress/block-editor`, pinned), loaded only on this route. The block library's WordPress REST calls are translated in the browser onto `/admin/api` (`src/main/webui/src/editor/wpapi.ts`; an unmapped call shows in the browser console as `Unknown WordPress API call: <METHOD> <path>`). A sidebar for status, visibility, date (in the site's time zone), slug, categories, tags, excerpt, author, comments and page parent and order; a media library with upload and alt text; Save draft, Submit for review (contributors), Publish, Schedule or Update by role; Preview; autosave every 60 s with "View the autosave" on reopen. A block's options menu turns it into a reusable block, and a reusable block's "Edit original" opens it at `/admin/blocks/{id}`. Classic (pre-Gutenberg) items open as one Classic block, edited in TinyMCE 8 with WordPress's classic-editor toolbar (downloaded only when such a block is on screen); they stay classic HTML when saved, and an unedited body is stored back byte for byte. They are never converted on their own: the block's "Convert to blocks" button does that on demand.
  - Reusable blocks (`/admin/blocks`): title, author, how many items use each and when it last changed; authors and up add new ones. `/admin/blocks/{id}` is the same editor bound to one block: Save sends `expectedModifiedAt` and reports how many items will be republished, with autosave and revisions as for posts. A block you may not edit opens read-only.
  - Posts and Pages lists (`/admin/posts`, `/admin/pages`): status tabs with counts, search, add, trash and restore.
  - A setup wizard on first start. It asks for the one-time setup token Stillpress prints to its log, creates the site and the first administrator, then offers **Start empty** or **Import from WordPress**.
  - WXR upload import: the file is uploaded from the admin and imported as a background job with live progress, then queued for publishing. A site is imported at most once, from the admin or the CLI: only into an empty site, and only when no import is running or done; a failed import that left nothing behind may be retried.
  - Users and roles (administrator, editor, author, contributor). Imported authors become accounts without a password. With SMTP set up, an administrator mails them an invite (a set-password link valid 7 days); without it, "Copy link" gives a link valid 24 hours, and `stillpress user reset-password <login>` prints one from the server. A new link replaces the user's earlier ones.
  - "Forgot password?" on the login screen mails a reset link valid 1 hour, at most one per account every 5 minutes. The answer is the same whether or not the account exists.
  - Submitting an item for review mails every editor and administrator who has an email.
  - Every user edits their own display name and email on **Profile** (`/admin/profile`). Each role sees only the screens it may use: a screen it may not (Users, Settings, Import and Redirects for non-administrators; Pages, Categories and Tags for authors and contributors) sends it to the dashboard. New users (`POST /admin/api/users`) get the same checks as profile edits: login of 1–60 letters, digits, spaces and `_ . - @`, email and name trimmed, 400 with `field` on bad input, 409 `login_taken` or `email_taken`.
  - **Reset site** at the bottom of Settings (administrators, `POST /admin/api/reset` with `{"password","confirm"}`; `confirm` is the site title): deletes all content, media, users other than you, categories, redirects and settings except the title, language, site address and time zone, publishes the empty site and opens import again. This is how to redo a bad import. The password counts towards the login rate limit; it is refused while an import runs.
  - Password login (Argon2id) with rate limits: 5 failures per account and 20 per client IP in 15 minutes, then 429 with `Retry-After`. Login, password reset and set-password share one per-IP limit (20 failures or reset requests per 15 minutes), so many resets from one address can briefly block logins from it.
  - CSRF protection by [quarkus-rest-csrf](https://quarkus.io/guides/security-csrf-prevention): a GET issues the token in the `X-CSRF-TOKEN` response header and its signature (keyed from the keyset) in the HttpOnly `csrf-token` cookie; every state-changing admin API call must send the token back in `X-CSRF-TOKEN`.
  - `/wp-admin` and `/wp-login.php` redirect to `/admin/`, so WordPress bookmarks keep working.
  - The admin in English and Russian, per user: the header's language switch stores it (`PUT /admin/api/session/locale` with `{"locale":"en"|"ru"}`, anything else 400); `GET /admin/api/session` returns the effective `locale` (the user's choice, else `ru` when the site language starts with `ru`, else `en`). Setup and login follow the browser's language. The block editor uses WordPress's own Russian translation, a filtered snapshot in `src/main/webui/src/i18n/wp-ru.json`; refresh it after a Gutenberg upgrade with `mise run i18n:wp`.
- `stillpress nginx-config`: the include for Mode 2, where nginx serves the published files (see below).

Not yet: attachment pages redirect to their parent post instead of rendering (WordPress renders them), and gallery plugins, shortcodes such as `[gallery]`, search and sitemaps are later plans.

## Requirements

- [mise](https://mise.jdx.dev/). It pins Liberica JDK 25, Maven and Bun (`mise.toml`).
- Docker. jOOQ code generation runs against a throwaway PostgreSQL container, and the test suite needs it too: the PostgreSQL tests use Quarkus Dev Services, and the Mode 2 test runs nginx in Testcontainers.
- Nothing else for the admin UI: Quinoa downloads the Bun version pinned in `application.properties` (kept equal to `mise.toml` by a test) and builds `src/main/webui` during the Maven build.

## Build

The generated jOOQ sources live in `target/` and are not committed, so a fresh clone needs codegen once (and again after any schema change):

```bash
mise exec -- mvn -Pcodegen generate-sources   # needs Docker
mise exec -- mvn verify                        # the full build: admin UI, all tests (Docker), formatting check
```

Formatting is [Prince of Space](https://github.com/agustafson/prince-of-space) via Spotless. `verify` fails on unformatted code. To fix it:

```bash
mise exec -- mvn spotless:apply
```

### Development

Day to day, run the app in Quarkus dev mode through the quarkus-agent MCP server (`.mcp.json`): `quarkus_start` starts it, and its Dev MCP test tools (`devui-testing_runTests`, `devui-testing_runTest` with a class name) run the tests with hot reload. [AGENTS.md](AGENTS.md) has the workflow. Keep `mise exec -- mvn verify` for the full build before pushing. The admin UI's own commands run with `bun` in `src/main/webui`; `mise run test:ui` runs its unit tests (Vitest, on Node), which the Maven build does not, `bun run mutation` runs mutation testing on them (Stryker), and `mise run verify` runs `mvn -q verify` and then `test:ui`, the whole check before pushing.

## Run

```bash
mise exec -- mvn package -DskipTests
alias stillpress='java -jar target/quarkus-app/quarkus-run.jar'

stillpress import --wxr export.xml     # one-shot; refuses a second import
stillpress publish                     # render a full release and make it live; consumes the publish queue
stillpress serve                       # serve the live release on :8080 (also the default command)
stillpress verify-urls --source https://old-blog.example --target http://localhost:8080
stillpress user reset-password <login>  # print a single-use set-password link (valid 24 h); login or email
stillpress secrets reset               # delete every stored secret (SMTP password, Turnstile secret) after losing the keyset; switches CAPTCHA off if it was Turnstile
stillpress captcha none                # turn the login/reset/setup CAPTCHA off (e.g. a broken Turnstile key)
stillpress reset                       # show what a reset would delete (row counts, data directory); exits 1
stillpress reset --yes                 # delete everything, users and settings included; the next serve prints a setup token
stillpress reset --yes --force         # the same after a crash mid-import left it marked running; stop the server first
```

On first start, `serve` logs a setup token (`Setup token: …`); open `/admin`, enter it in the wizard and create the site there, empty or from a WXR upload. The token exists only until the first administrator has a password.

`import` options:

- `--media-limit N` downloads at most N media files. It is for test imports; a full import (no flag) downloads everything.
- `--posts-per-page N` (default 10), `--posts-per-rss N` (default 10), `--category-base BASE` (default `category`) and `--tag-base BASE` (default `tag`) carry the WordPress settings an export does not include. Set them to the source site's values, or archive URLs and page counts will differ. The bases are a single path segment.

The admin (wizard) import has no flag; set `STILLPRESS_IMPORT_MEDIA_LIMIT=N` (property `stillpress.import.media-limit`, unset = unlimited) to cap its media downloads for a test run.

`verify-urls` takes `--source` (the live WordPress site), `--target` (Stillpress) and `--max N` (default 20000 URLs). It exits 0 only when every URL matches.

Only `serve` binds the HTTP port, and the H2 database runs in auto-server mode. `publish` and `verify-urls` therefore run while `serve` is up; a new release is picked up without a restart. Pass `-Dquarkus.http.port=…` to serve elsewhere.

### Serving in production

The default is **nginx serving `data/output/current` directly** and falling back to Stillpress for everything that is not a file: redirects, legacy `?p=` URLs, the 404 page and the admin ([ADR 0004](docs/adr/0004-nginx-serves-static-by-default.md)). Stillpress generates the nginx include:

```bash
java -jar target/quarkus-app/quarkus-run.jar nginx-config > /etc/nginx/stillpress.conf
```

Options: `--root <path>` is the site root as nginx sees it (default: the data directory's `output/current`), `--upstream <host:port>` is where Stillpress listens (default `127.0.0.1:8080`). Include the file inside your own `server {}` block, next to `listen`, `server_name` and TLS; [docs/deploy/nginx-site.conf.example](docs/deploy/nginx-site.conf.example) shows a whole one. nginx needs read access to `output/`. The include holds no site data, so it is generated once: publishing swaps the `current` symlink atomically and nginx picks the new release up without a reload.

Stillpress takes the client IP and scheme from nginx's `X-Forwarded-*` headers only when the connection comes from a trusted proxy: `127.0.0.1` and `::1` by default. If nginx connects from another address (another host or container), list it in `STILLPRESS_TRUSTED_PROXIES` (comma-separated addresses or CIDRs; IPv6 in brackets, e.g. `[::1]`). nginx in a Docker container connects from the bridge network, so it always needs this, for example `STILLPRESS_TRUSTED_PROXIES=172.17.0.0/16`.

Mode 1 still works for the simplest setups: run `serve` behind any TLS-terminating reverse proxy and it serves the files itself. That proxy must overwrite `X-Forwarded-For` with the client address (nginx: `proxy_set_header X-Forwarded-For $remote_addr;`), never append to it (`$proxy_add_x_forwarded_for`): Stillpress takes the leftmost entry, so an appended header lets any client pick its own IP and dodge the login rate limit.

The public admin forms (login, password reset, set-password, setup) take a CAPTCHA chosen in the admin: none (default), Cloudflare Turnstile or self-hosted ALTCHA, plus an always-on honeypot. `STILLPRESS_CAPTCHA_PROVIDER` (`none`, `turnstile`, `altcha`) overrides and locks that choice; with it set, the Turnstile keys come from `STILLPRESS_TURNSTILE_SITE_KEY` and `STILLPRESS_TURNSTILE_SECRET`.

Data lives in `./data`, or wherever `STILLPRESS_DATA_DIR` points: the H2 database, `keys/`, `media/` and `output/`.

#### Keys

Stored secrets (the SMTP password, the Turnstile secret) are encrypted with [Tink](https://developers.google.com/tink) keysets, which also sign the CSRF cookie and key the ALTCHA challenges. On first start Stillpress creates `aead.json` and `prf.json` in `<data dir>/keys/`. **Back them up together with the database**: the database alone can't decrypt its secrets. `STILLPRESS_KEYS_DIR` points at another directory (a mounted secret, say); Stillpress only reads it and never creates keys there, so copy the two generated files into it. Replicas must share one.

If the keyset is lost, every command but `secrets` refuses to start and says so. Restore the two files, or run `stillpress secrets reset` to drop the stored secrets and start with a new keyset, then re-enter the SMTP password and Turnstile secret in the admin.

#### Mail and the admin address

SMTP is set up in the admin, in the Email section of the Security screen: host, port, security, login, password and from address, with a test mail. Mail never blocks or fails the action that sends it; the last error shows there.

Every mailed or printed link starts with the **Admin address** setting (Settings), never with the host a request came in on. Setup fills it from the address the wizard was opened at, and so does an administrator's first visit after an upgrade. Before cutover that's the temporary host, so **change the Admin address when the site moves** to its own domain, or invites and resets keep pointing at the old one.

### Database

The default is an embedded H2 file, with nothing to install. Its URL is set for the `dev`, `test` and `prod` profiles only, so a custom profile must include `prod` (for example `QUARKUS_PROFILE=prod,staging`) or set `quarkus.datasource.jdbc.url` itself. The database kind is fixed when the app is built, so PostgreSQL needs its own build; the URL and credentials are runtime settings. The jOOQ dialect follows the datasource kind automatically.

```bash
mise exec -- mvn package -DskipTests -Dquarkus.datasource.db-kind=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/stillpress \
QUARKUS_DATASOURCE_USERNAME=stillpress QUARKUS_DATASOURCE_PASSWORD=… \
  java -jar target/quarkus-app/quarkus-run.jar serve
```

## Conventions

- JDK imports are JEP 511 module imports (`import module java.base;`). Library types keep single-type imports.
- Schema changes go in portable Flyway migrations under `src/main/resources/db/migration/common`.
- The admin front end is TypeScript; use `bun`, never npm.
- Tools come from mise, and common tasks are mise tasks. When mise tasks are not enough, go-task is installed as a mise tool and its `Taskfile.yml` holds those tasks.
- Renovate keeps dependencies current. The config includes custom managers for the Liberica JDK pin in `mise.toml`, the Prince of Space version and Quinoa's Bun pin, which moves in one PR with `mise.toml`'s Bun.

## Documentation

- [Design](docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md)
- [Roadmap: plans 02–05](docs/superpowers/plans/ROADMAP.md)
- [Plan 01 results on a real blog](docs/superpowers/plans/2026-11-03-stillpress-01-results.md)
- [Plan 02a results on the real blog through nginx](docs/superpowers/plans/2026-11-04-stillpress-02a-results.md)
- [Plan 02c-1 results](docs/superpowers/plans/2026-11-14-stillpress-02c-1-results.md)
- [Glossary](CONTEXT.md)
- [Architecture decisions](docs/adr/)

## Licence

GPL-2.0-or-later. See [LICENSE](LICENSE).
