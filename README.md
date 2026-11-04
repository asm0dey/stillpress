# Stillpress

A self-hostable CMS on Quarkus that replaces WordPress for blogs. Authors write in the Gutenberg block editor inside the app; readers only ever get pre-rendered static files. Existing WordPress sites come in through a one-shot import and keep their URLs and their look.

One install runs one site. Several sites means several installs.

> **Status: early development.** Plan 01 (the core pipeline: import → store → render → publish → serve) is done and has been run against a real blog: every post, page, archive, pagination page and feed URL matches the live WordPress site ([results](docs/superpowers/plans/2026-11-03-stillpress-01-results.md)). Plan 02a (the admin foundation: setup wizard, login, users, wizard import, nginx Mode 2) is done and has been run against the same blog through nginx ([results](docs/superpowers/plans/2026-11-04-stillpress-02a-results.md)). There is no editor or theme yet; pages use a minimal built-in "plain" layout. Plan 02b (editing and publishing) is next; the rest is in the [roadmap](docs/superpowers/plans/ROADMAP.md).

## What works today

- Parsing WordPress block markup, as a port of `WP_Block_Parser`.
- Rendering blocks: static blocks pass through byte for byte, reusable blocks are resolved, and YouTube embeds become click-to-load facades.
- Classic (pre-Gutenberg) content: ports of `wpautop()`, `shortcode_unautop()` and the `[caption]` shortcode.
- Streaming the WXR export reader: multi-GB exports never sit in memory.
- A one-shot importer: posts, pages, attachments, terms, authors, comments and reusable blocks.
  - Media is downloaded into `data/media/`, and its URLs are rewritten in HTML and block JSON, Jetpack Photon (`i0.wp.com`) links included.
  - A redirect map is built for `?p=`, `?page_id=`, `?attachment_id=` and attachment pages.
- The publisher: every published post and page, home, category, tag, author and date archives (paginated), and RSS feeds, rendered into a fresh release that goes live atomically.
- Serving the live release with WordPress-compatible redirects as the fallback.
- A URL parity checker that crawls the live site's sitemaps and feeds and compares every URL against Stillpress.
- The admin at `/admin`, written in TypeScript (React, built by Bun through Quinoa):
  - A setup wizard on first start. It asks for the one-time setup token Stillpress prints to its log, creates the site and the first administrator, then offers **Start empty** or **Import from WordPress**.
  - WXR upload import: the file is uploaded from the admin and imported as a background job with live progress, then published. A site is imported at most once, from the admin or the CLI: only into an empty site, and only when no import is running or done; a failed import that left nothing behind may be retried.
  - Users and roles (administrator, editor, author, contributor). Imported authors become accounts without a password; an administrator issues them a single-use set-password link, and `stillpress user reset-password <login>` prints one from the server.
  - Password login (Argon2id) with rate limits: 5 failures per account and 20 per client IP in 15 minutes, then 429 with `Retry-After`.
  - CSRF protection by [quarkus-rest-csrf](https://quarkus.io/guides/security-csrf-prevention): every state-changing admin API call must echo the `csrf-token` cookie in `X-CSRF-TOKEN`.
  - `/wp-admin` and `/wp-login.php` redirect to `/admin/`, so WordPress bookmarks keep working.
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

Day to day, run the app in Quarkus dev mode through the quarkus-agent MCP server (`.mcp.json`): `quarkus_start` starts it, and its Dev MCP test tools (`devui-testing_runTests`, `devui-testing_runTest` with a class name) run the tests with hot reload. [AGENTS.md](AGENTS.md) has the workflow. Keep `mise exec -- mvn verify` for the full build before pushing. The admin UI's own commands run with `bun` in `src/main/webui`.

## Run

```bash
mise exec -- mvn package -DskipTests
alias stillpress='java -jar target/quarkus-app/quarkus-run.jar'

stillpress import --wxr export.xml     # one-shot; refuses a second import
stillpress publish                     # render a new release and make it live
stillpress serve                       # serve the live release on :8080 (also the default command)
stillpress verify-urls --source https://old-blog.example --target http://localhost:8080
stillpress user reset-password <login>  # print a single-use set-password link (valid 24 h); login or email
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

Data lives in `./data`, or wherever `STILLPRESS_DATA_DIR` points: the H2 database, `media/` and `output/`.

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
- [Glossary](CONTEXT.md)
- [Architecture decisions](docs/adr/)

## Licence

GPL-2.0-or-later. See [LICENSE](LICENSE).
