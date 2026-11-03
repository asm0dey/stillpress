# Stillpress

A self-hostable CMS on Quarkus that replaces WordPress for blogs. Authors write in the Gutenberg block editor inside the app; readers only ever get pre-rendered static files. Existing WordPress sites come in through a one-shot import and keep their URLs and their look.

One install runs one site. Several sites means several installs.

> **Status: early development.** Plan 1 of 5 (the core pipeline: import → store → render → publish → serve) is being built. There is no admin UI, editor or theme engine yet. See [the design](docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md) and [plan 1](docs/superpowers/plans/2026-11-03-stillpress-01-core-pipeline.md).

## What works today

- Parsing WordPress block markup, as a port of `WP_Block_Parser`.
- Rendering blocks: static blocks pass through byte for byte, reusable blocks are resolved, and YouTube embeds become click-to-load facades.
- Classic (pre-Gutenberg) content: ports of `wpautop()`, `shortcode_unautop()` and the `[caption]` shortcode.
- Streaming the WXR export reader: multi-GB exports never sit in memory.
- A one-shot importer: posts, pages, attachments, terms, authors, comments and reusable blocks.
  - Media is downloaded into `data/media/`, and its URLs are rewritten in HTML and block JSON.
  - A redirect map is built for `?p=`, `?page_id=`, `?attachment_id=` and attachment pages.

Still to come in plan 1: page templates and feeds, the atomic static publisher, serving with a redirect fallback, and a URL parity checker against the live site.

## Requirements

- [mise](https://mise.jdx.dev/). It pins Liberica JDK 25 and Maven (`mise.toml`).
- Docker. It's only needed to generate the jOOQ classes; the schema is generated against a throwaway PostgreSQL container.

## Build

The generated jOOQ sources live in `target/` and are not committed, so a fresh clone needs codegen once (and again after any schema change):

```bash
mise exec -- mvn -Pcodegen generate-sources   # needs Docker
mise exec -- mvn verify                        # tests + formatting check
```

Formatting is [Prince of Space](https://github.com/agustafson/prince-of-space) via Spotless. `verify` fails on unformatted code. To fix it:

```bash
mise exec -- mvn spotless:apply
```

## Run

```bash
mise exec -- mvn package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar import --wxr export.xml   # one-shot; refuses a second import
java -jar target/quarkus-app/quarkus-run.jar                          # serve (default command)
```

Data lives in `./data`, or wherever `STILLPRESS_DATA_DIR` points: the H2 database, `media/` and `output/`.

### Database

The default is an embedded H2 file, with nothing to install. To use PostgreSQL, set the datasource kind and URL. The jOOQ dialect follows the datasource kind automatically.

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/stillpress
```

## Conventions

- JDK imports are JEP 511 module imports (`import module java.base;`). Library types keep single-type imports.
- Schema changes go in portable Flyway migrations under `src/main/resources/db/migration/common`.
- Renovate keeps dependencies current. The config includes custom managers for the Liberica JDK pin in `mise.toml` and the Prince of Space version.

## Documentation

- [Design](docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md)
- [Glossary](CONTEXT.md)
- [Architecture decisions](docs/adr/)

## Licence

GPL-2.0-or-later. See [LICENSE](LICENSE).
