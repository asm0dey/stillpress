# Stillpress — a WordPress-replacement static CMS on Quarkus — Design

Date: 2026-11-03
Status: draft, builder-checked, grilled, awaiting approval

## 1. Intent

**Stillpress** is a self-hostable CMS, written in Quarkus, that **replaces WordPress** for
blogs. Authors write in a Gutenberg block editor inside the app; the app
publishes every site as **static files**. Existing WordPress sites come in
through import (WXR export file, or the REST API of a live site) and keep
their **page URLs** and their **look**.

First users: the author's own three WordPress sites, which must move off
WordPress without readers noticing:

| Site | Domain | Theme today | Character |
|---|---|---|---|
| blog | blog.asm0dey.ru | `saga-wpcom` | personal/family blog, photo-heavy (91 posts, many galleries), Russian, Cyrillic slugs |
| it | it.asm0dey.ru | `shift` | tech blog + podcast (Seriously Simple Podcasting), code highlighting |
| newpodcast | newpodcast2.live | `penscratch-2-wpcom` | podcast site |

The product must also be usable and extendable **by other people** with
their own WordPress content — it is not a one-off migration script.

## 2. Goals

1. **Replace WordPress** as the place where posts are written, edited, and
   managed. One install runs one site; several sites = several installs.
2. **Static output.** Readers only ever get pre-rendered files. Post/page
   URLs are preserved exactly as on the source WordPress (including
   percent-encoded Cyrillic slugs, date-based permalinks, and the podcast
   feed URL). Asset URLs (images, media, CSS, JS) are free to change.
3. **Import** from (a) a WordPress WXR export file and (b) a live WordPress
   site via REST API. Import must preserve raw block markup, so it must use
   WXR or **authenticated** REST (`context=edit`); public rendered HTML is
   lossy (spike: galleries collapsed into loose images and raw HTML).
   Site configuration (appearance, menus, widgets, plugin settings) is
   imported too — see §11 for the three sources, including the export
   helper plugin.
4. **Gutenberg-style block editing**, including reusable blocks (synced
   patterns), galleries, embeds, columns/groups, and custom blocks.
5. **Theming as flexible as WordPress's** (see §6) — enough that the three
   current themes can be rebuilt so the sites look the same or very close.
6. **Built-in plugins** for the features the sites actually use, each
   **enable/disable-able per site** (see §7).
7. **Comments**: existing comments (blog, it) are imported and displayed
   with their posts, and **sites with comments turned on accept new ones**
   through native, moderated commenting (§6.2.3).
8. The app **stores generated output next to itself** and **can serve it**
   directly; the output directory can equally be served by nginx or any
   static server.

## 3. Non-goals

- Running PHP, WordPress themes, or WordPress plugins. Themes and plugins
  are re-implemented natively.
- A third-party plugin ecosystem / public plugin API. Crucial plugins live
  inside the app.
- Keeping asset paths (`/wp-content/uploads/...`) stable.
- Two-way sync with a still-running WordPress, and **second imports**.
  Import is a one-shot migration step; afterwards the app is the only
  source of truth.
- AMP, WordPress backups (UpdraftPlus), ManageWP, object caches, nginx
  cache purging, image-regeneration tooling — WordPress-operations plugins
  that disappear with WordPress.

## 4. Anti-goals (would make it a failure even if it "works")

- A reader can tell the site changed: broken links, changed post URLs,
  visibly different layout, broken galleries.
- **Podcast subscribers lose the feed**: the feed URL or its format changes
  and Apple/Spotify stop delivering episodes.
- Imported posts silently lose content (galleries flattened, blocks dropped)
  without the import report saying so.
- The editor becomes a worse place to write than the WordPress editor it
  replaces.
- Theme authors need to write Java to make a theme.

## 5. Approaches considered (editor & content model)

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | **Embed the real Gutenberg editor** (`@wordpress/block-editor` & friends, standalone) in the admin; Java owns storage, rendering, publishing. | Familiar editing; reusable blocks, galleries, embeds, columns built-in; imported markup is native (no converter); static blocks carry their own final HTML so look-parity is cheap. Cost: large, fast-moving React dependency (pin versions); must emulate a slice of the WP REST contract; must generate layout/global-styles CSS that WordPress makes in PHP. |
| B | Own block editor on TipTap/ProseMirror | Full control, lighter front end; but every block's editing UI built from scratch (months to parity) and a lossy converter from Gutenberg markup. |
| C | Editor.js | Thin ecosystem for galleries/layout; same converter problem as B. |

The author chose **A** after a throwaway spike (see §13) confirmed it works
without PHP.

## 6. Architecture

### 6.1 Stack (decided)

- **Quarkus** (Java 25 LTS), Maven.
- **mise** manages every tool for the whole project (Java, Maven, Bun),
  pinned in `mise.toml`; common tasks are mise tasks.
- **Admin front end built with Bun**, integrated via **Quarkus Quinoa**
  (Vite). Known wrinkle: Quinoa only supports Bun by downloading its own
  copy (`quarkus.quinoa.package-manager-install.bun-version`); keep that
  version equal to `mise.toml` and enforce it with a build check.
- **Admin UI design** follows the **ui-ux-pro-max** skill (installed).
- **Qute** for theme templates (server-side rendering of static output).
- **Storage (ADR 0002):** H2 file mode by default — one data directory
  beside the app holding the database file, media, and every site's output
  directory, so backup = copy a folder; PostgreSQL supported as a second
  engine. jOOQ for data access, Flyway owns the schema, schema kept
  portable between the two engines.

### 6.2 Components

1. **Content store** — sites, posts, pages, custom post types (podcast
   episodes), revisions, taxonomies (categories, tags, podcast series),
   media library, comments (read-only), reusable blocks, menus, widget
   areas, per-site settings, per-site plugin enablement, users.
2. **Admin backend (REST)** — app-native API for the admin UI, **plus** the
   slice of the WordPress REST contract the embedded editor requires
   (discovered in the spike):
   - `wp/v2/blocks` CRUD (synced patterns), `wp/v2/types`, `wp/v2/themes`
     (active theme + theme supports), `oembed/1.0/proxy`, media list/upload,
     and whatever further calls the editor makes (log unknown `/wp-json`
     calls in dev to find them).
   - Accept `POST` + `X-HTTP-Method-Override` (apiFetch rewrites PUT/DELETE).
3. **Admin front end** — Gutenberg editor (posts, pages, episodes), a
   pattern editor screen (synced patterns are locked inside posts, so "edit
   original" opens a separate editor bound to the `wp_block` entity), media
   library, menus, widgets, site/theme/plugin settings, import screens,
   publish status. Save must persist dirty `core-data` entities (edited
   patterns) as well as the post.
4. **Importers** (run from the setup wizard) — WXR file upload; live-site REST import with an
   application password (authenticated, `context=edit`). Imports posts,
   pages, podcast episodes and their metadata, comments, reusable blocks,
   menus, categories/tags, media (downloaded and stored under app-chosen
   paths; references in content rewritten), authors, and the source
   permalink of every item. Produces an **import report** listing anything
   unrecognised (unknown blocks, shortcodes, missing media). **Import is
   one-shot per site**: you import once, then the app replaces WordPress.
   Re-import into an existing site is not supported; to redo a bad import,
   wipe the install's data directory and run setup again.
5. **Block renderer (Java)** — parses Gutenberg block-comment markup.
   Static blocks emit their saved HTML unchanged (spike-proven). Dynamic
   blocks get Java renderers: reusable-block references, embeds (YouTube →
   click-to-load facade, privacy-friendly), latest posts/archives/etc. as
   needed, plugin blocks (podcast player, tiled gallery, 360°, code).
   Classic-editor (non-block) posts render through a cleanup pass
   (autop-equivalent, shortcode handling for shortcodes actually present).
   Blocks belonging to a **disabled** plugin render a plain fallback rather
   than failing the publish.
6. **Style generator** — produces the CSS WordPress generates in PHP:
   layout support (`is-layout-flex`, `wp-container-*`), global styles from
   a theme's `theme.json`-equivalent, block-supports styles. Same output
   feeds the editor canvas (so galleries look right while editing — the
   spike showed galleries as a single column without it) and the static
   site.
7. **Theme engine** — see §6.3.
8. **Static publisher** — renders every URL of a site into the site's
   output directory next to the app: posts, pages, archives (date,
   category, tag, author), pagination, home, feeds (RSS/Atom, per-category,
   **podcast feed at its existing URL and format**), sitemap, robots,
   redirects map, 404 page, and copies/optimises assets. Incremental
   republish on change (a post edit re-renders that post plus the listing
   pages, feeds, and pages embedding a changed reusable block). Full
   rebuild on theme/settings change.
9. **Serving — two supported modes:**
   - **Mode 1 (default): Stillpress serves everything** — static files from
     its output directory plus all dynamic paths; any reverse proxy (e.g.
     nginx-proxy-manager) only terminates TLS and forwards. Simplest; the
     site is down while Stillpress is down.
   - **Mode 2: nginx serves static files directly** from the output
     directory (shared read-only volume) and proxies only dynamic traffic
     to Stillpress: `/admin`, `/wp-admin`, `/wp-login.php`, admin API,
     preview, comment submission, ALTCHA challenge, `/podcast-download/`,
     legacy query URLs (`?p=`, `?page_id=`, `?cat=` …), and **anything not
     found as a file** (`try_files $uri $uri/ @stillpress`), which is how
     the redirect map and the real 404 page work.
   - Stillpress generates the Mode 2 nginx include **once**; it is static —
     **no nginx reload on publish**. Redirects live in Stillpress (reached
     via the fallback), and atomic republish swaps an `output/current`
     symlink that nginx's root points at.
   - In Mode 2 readers keep the whole site while Stillpress restarts; only
     admin, new comments and download counting pause (audio links fall
     back to nginx's static copy).
10. **Admin per domain (decided)** — every site's admin lives on that
    site's own domain at **`/admin`** (like `/wp-admin`); `/wp-admin` and
    `/wp-login.php` redirect there. Login sessions are per domain.

### 6.2.1 Publishing workflow (decided)

- Item statuses as in WordPress: draft, pending review, scheduled,
  published, private.
- **Publish / Update** immediately and incrementally rewrites the affected
  static files: the item, plus every listing page, archive and feed it
  appears in. Editing a **reusable block** republishes every item using it.
- **Preview** is rendered on the fly by the app for logged-in users; never
  written to the output directory.
- **Appearance changes** (style overrides, theme switch, menus, site
  settings) are saved in the Site Editor/admin and trigger a **background
  full-site republish** with progress shown in the admin. The republish is
  **atomic**: it renders into a fresh directory and swaps it in, so readers
  never see a half-updated site.

### 6.2.2 Admin authentication (decided)

- Username/email + password (Argon2id/bcrypt). Per-account and per-IP
  rate limiting on login.
- Optional per-user 2FA: **TOTP** and **passkeys**; administrators can make
  2FA mandatory for the site.
- **SMTP configured in the admin** for password reset, invitations and
  "pending review" notifications; without SMTP, resets via a server CLI
  command.
- Imported WordPress users arrive **without usable passwords** (old hashes
  are not carried over); each sets a password via invite/reset email.
- **CAPTCHA on the public-facing admin forms** (login, password reset,
  setup wizard), modelled on calit:
  - one provider switch: **`none | turnstile | altcha`**; enabling a
    provider turns on its widget **and** server-side verification together
    — never a widget whose token goes unchecked;
  - **ALTCHA** = self-hosted proof-of-work: `org.altcha:altcha` server
    library, a public signed-challenge endpoint (5-minute expiry), HMAC key
    **required** (fail fast at startup/save without it), widget from a
    pinned WebJar (calit found widget 3.4.0 broke v1 challenges);
  - **Turnstile** = Cloudflare site key + secret, verified server-side via
    `siteverify`;
  - an always-on **honeypot** field;
  - the provider is a site setting in the admin (environment variables
    can preset/override it); saving a provider verifies its keys first,
    and a server CLI command can switch it to `none`, so a bad key can't
    lock everyone out;
  - privacy-policy text reflects the active provider, as in calit.
- External login (OIDC) not in v1; can be added later without changing the
  above.

### 6.2.3 Comments (decided)

On sites (and posts) with comments enabled — mirroring WordPress's
discussion settings, imported from the source site:

- A comment form under each post submits to Stillpress on the same domain,
  protected by the site's CAPTCHA provider (§6.2.2) and the honeypot.
- New comments enter a **moderation queue** in the admin with WordPress's
  rules: hold first-time commenters, auto-approve after a previously
  approved comment, email notifications to authors/moderators, threaded
  replies, close per post or automatically after N days.
- **Approval republishes** the post's page (and the comment feed), so
  readers still only receive static files.
- In serving Mode 2 the generated nginx include proxies comment submission
  to Stillpress (§6.2 item 9).

### 6.2.6 Revisions and autosave (decided)

- A **revision** is stored on every save/publish of an item, reusable
  block, template, template part and style overrides (author + time).
- **Autosave** while editing (~1 min) with "restore newer autosave?" on
  reopen.
- **Revision browser**: side-by-side compare and restore; restoring a
  published item's revision is a normal Update.
- Retention: last **25** per object by default, configurable.
- WordPress revisions are not imported (not in WXR); history starts at
  import.

### 6.2.5 Distribution (decided)

- **JVM only**: multi-arch (amd64 + arm64) container image on GHCR, on a
  hardened distroless Liberica JRE base with a JDK AOT cache (as in the
  author's other services). **GraalVM native image is not a goal** — no
  reachability metadata work, free choice of image-processing libraries.
- One volume = the install's data directory (H2 file, media, output).
- Example `compose.yaml` for one site, with optional PostgreSQL and the
  nginx variant using the generated include.
- The **export helper WordPress plugin** ships as a zip on each release.

### 6.2.4 Languages (decided)

- Admin UI translatable, **per-user language**; v1 ships **English and
  Russian**. Gutenberg's strings come from WordPress's community
  translations.
- **One language per site** (e.g. `ru`), driving theme strings, dates,
  pagination labels, `<html lang>`, feeds and search. Themes ship
  translation files; the three rebuilt themes include Russian.
- Multilingual sites (one item in several languages) are out of scope.

### 6.3 Theming — "as flexible as WordPress"

**Author's bar:** a non-technical user can create content **and tune the
appearance** as well as they can in WordPress today — visually, in the
admin, without touching files. For block themes (Saga is one) this means a
**Site Editor in v1**: visual editing of global styles (colours,
typography, spacing, layout widths, per-block styles), templates, and
template parts, using Gutenberg's own site-editing components, with changes
stored as per-site overrides of the theme's defaults (as WordPress stores
`wp_template`, `wp_template_part`, `wp_global_styles`). The spike showed
the blog's REST API already exposes these entity types.

Theme model — mirror WordPress, without executing arbitrary code:

- **Template hierarchy** with the same fallback rules (e.g.
  `single-{post_type}-{slug}` → `single-{post_type}` → `single` →
  `singular` → `index`; category/tag/date/author/archive/home/front-page/
  page/404/search).
- **Template parts** (header, footer, sidebar, comments, …) overridable
  individually.
- **Child themes** inheriting from a parent and overriding any file.
- **Menus** (locations declared by the theme) and **widget areas**.
- **Theme settings** (customizer-equivalent: colours, fonts, logo, header
  image, layout options) declared by the theme, edited in admin.
- **theme.json-equivalent** for global styles, palette, typography,
  layout widths, block style variations — feeding §6.2.6.
- **Block themes are the primary model**: templates and parts are block
  markup, styles come from `theme.json`, both rendered by the Java block
  renderer and editable in the Site Editor. Classic-style themes (Qute
  templates + CSS + JS + a manifest, customizer-style settings, menus,
  widget areas) are a secondary model for themes like Shift/Penscratch 2
  if rebuilding them as block themes isn't faithful enough — builder's call
  per theme. No Java required to write a theme either way.
- Ship rebuilt **saga**, **shift**, **penscratch-2** so the three sites
  look the same or close; plus one neutral default theme.

### 6.4 One install = one site (ADR 0003)

Each Stillpress install runs **exactly one site** with its own data
directory (H2 file or its own PostgreSQL database, media, output). Hosting
several sites means running several installs (e.g. one container each
behind a reverse proxy). Sites therefore know nothing about each other by
construction: separate admin, user accounts, content, settings.

**Creating a site = starting an install.** The first visit to `/admin`
runs a **setup wizard** — site title, language, first administrator
account, then *Start empty* or *Import from WordPress* (WXR upload, REST
with an application password, export-helper bundle). The wizard requires a
**one-time setup token** printed in the server log, so whoever reaches a
fresh domain first cannot claim it. Redoing a bad import = wiping the
install's data directory and starting over.

## 7. Built-in plugins (enable/disable per site)

Derived from what is actually active on the three sites (public REST
namespaces + homepage assets; admin-only plugins may be missing from this
list):

| Plugin | Replaces | Used by |
|---|---|---|
| Podcasting: episodes, series, speakers, player block, podcast RSS feed (iTunes/Podcasting 2.0 tags) at the **unchanged feed URL**, **download counting** (see below) | Seriously Simple Podcasting (+Speakers, +Stats) | it, newpodcast |
| SEO: title/meta/description, Open Graph/Twitter, canonical, JSON-LD | Yoast, The SEO Framework, Jetpack OG | all |
| Sitemap | Yoast / Google Sitemap Generator | all |
| Galleries: core gallery + **Jetpack Tiled Gallery** (mosaic/rectangular, square, circle, columns; rounded corners); layout computed from image sizes at publish time | Jetpack | blog |
| 360° panorama block | dimage-360 | blog |
| Code syntax highlighting | Enlighter | it |
| Header/footer snippets | WebberZone Add to All | blog, newpodcast |
| Comments: render imported comments (threaded) + native moderated commenting | WP comments | blog, it |
| Embeds (YouTube etc., click-to-load) | core embeds | all |
| Analytics snippet (e.g. Umami — already self-hosted) | Independent Analytics, Jetpack Stats | it, newpodcast |

Not carried over: Akismet/reCAPTCHA (replaced by ALTCHA/Turnstile +
honeypot + moderation), AMP,
UpdraftPlus, ManageWP worker, Health Check, Redis cache, nginx-helper,
Regenerate Thumbnails, Robin Image Optimizer (becomes a publish step),
Simply Static (the app itself), WP 2FA / WP Mail SMTP (app auth and mail are
the app's own concern).

**Podcast download counting (decided):** `/podcast-download/<id>/<file>`
is handled by Stillpress (proxied by the generated nginx include when nginx
serves the site): it records the download, then **302-redirects to the
static audio file** (never streams audio itself). Counts are de-duplicated
per IP + user-agent per episode per day (IAB-like), ignoring range-request
repeats and known bots; only a daily dedupe hash is kept, no raw IPs. Admin
shows downloads per episode and over time with a coarse client breakdown.
Historic Seriously Simple Stats data is imported via the export helper when
present. Ships with podcasting in the second milestone.

Plugins are an **internal** module boundary (blocks, renderers, settings,
feeds, hooks into publish), not a public extension API.

## 8. Content facts that constrain the build

- blog: 91 posts; **51 are classic-editor HTML** (no blocks), the rest
  block-based. Galleries are the dominant rich content: core Gallery (old
  and nested-image variants, several per post) and ~25 Jetpack Tiled
  Galleries — whose HTML survives in posts even though Jetpack is no longer
  installed on blog. Also video blocks (8 posts), 360° panoramas (2),
  columns/groups.
- it / newpodcast: REST API began returning 403 during the scan (rate
  limiting or bot protection); full inventory needs a WXR export.
- Stored block markup keeps WordPress's `wp-block-*` class names — this is
  intentional: they are the CSS contract the block library and the themes
  rely on.

### 8.1 Podcast feed facts (checked against the live public feeds)

- `/feed/podcast/` **redirects** to a per-series feed URL (e.g.
  `/feed/podcast/<percent-encoded-series-slug>/`). Both the generic URL and
  the redirect target must keep working.
- Enclosures use Seriously Simple Podcasting tracking URLs on the site's own
  domain: `https://newpodcast2.live/podcast-download/<id>/<file>.mp3`.
  Podcast apps have stored these, so **`/podcast-download/<id>/<file>` URLs
  are page-class URLs that must keep resolving** (serve or redirect to the
  audio), despite the general "asset paths are free" rule.
- Item GUIDs are `https://<host>/?post_type=podcast&p=<id>` (not
  permalinks); they must be imported and emitted **verbatim**, or apps
  re-download every episode.
- "Unchanged format" means same channel/item data and identifiers, not
  byte-identical XML.

## 9. Constraints

- Post/page/feed URLs byte-for-byte identical to the source.
- Look and feel the same or close to the current themes.
- No PHP at runtime or build time.
- Self-hostable by others; nothing hardcoded to the author's sites.
- Gutenberg packages pinned; upgrades deliberate (block deprecations).

## 10. Decided by the author

- Quarkus; app replaces WordPress (authoring happens in the app).
- Sites fully isolated; process sharing unimportant → one install = one
  site; site created via token-protected first-run setup wizard.
- Theming as flexible as WordPress; no plugin ecosystem; crucial plugins
  built in; plugins toggleable.
- WXR import required; REST import of a live site.
- Format of stored content is free; look and feel must match.
- Existing comments kept.
- YouTube embeds; editable posts; reusable blocks.
- Output stored next to the app; app can serve it.
- Bun for the admin front end; mise for the whole project.
- ui-ux-pro-max for design.
- Non-technical users tune appearance visually in the admin as well as in
  WordPress today → Site Editor (global styles, templates, parts) in v1.
- New reader comments on comment-enabled sites: native, moderated,
  CAPTCHA-protected, approval republishes the page (§6.2.3).
- Built-in podcast download counting behind `/podcast-download/` (§7).
- Revisions + autosave with compare/restore, last 25 kept (§6.2.6).
- Distribution: JVM container image (multi-arch, GHCR) only; native image
  is not a goal (§6.2.5).
- Languages: admin in English + Russian, per-user; one language per
  site; no multilingual sites (§6.2.4).
- Admin login: password + optional TOTP/passkeys, SMTP-driven reset and
  invites, and **ALTCHA / Turnstile CAPTCHA support as in calit** (§6.2.2).
- Classic-editor posts import untouched and render as today; in the editor
  they open as a single **Classic block** (TinyMCE, as in WordPress) with
  **"Convert to blocks" on demand**. Never auto-converted.
- **Multiple users with WordPress-style roles** (administrator, editor,
  author, contributor); **accounts belong to one site only** (sites are
  isolated, see §6.4); imported WordPress authors
  become accounts (bylines, author archives, "my posts"). The editor's
  user/capability shims (`wp/v2/users`, `canUser`) are backed by these.
- **Site configuration is imported, not re-entered.** Three sources,
  combined: (1) WXR export for content; (2) authenticated REST
  (application password) for settings, menus, widgets/sidebars, global
  styles, templates, template parts, navigation, custom CSS; (3) a **small
  export helper WordPress plugin** (shipped and maintained with this
  project) that bundles what REST doesn't expose — classic theme mods,
  SEO plugin settings (Yoast, The SEO Framework), podcast channel/series
  settings (Seriously Simple Podcasting), Enlighter, Add-to-All snippets —
  into a file the importer reads. The import report lists anything still
  unmapped.
- **Licence: GPL-2.0-or-later** (same as WordPress). Gutenberg packages,
  Saga/Shift/Penscratch 2 templates and CSS, and Jetpack's tiled-gallery
  code may be reused directly, with attribution.
- **Migration order: blog first**, then it and newpodcast once podcasting
  is built. So the first milestone must cover: WXR + REST + helper-plugin
  import, Gutenberg editing incl. Classic block, core and Jetpack tiled
  galleries, 360° panorama, video, YouTube, reusable blocks, Saga rebuilt
  as a block theme with Site Editor appearance tuning, comments archive,
  SEO/sitemap/snippets, multi-user roles, static publish + serve, redirect
  map, search. Podcasting and code highlighting come in the next milestone.
  No deadline was given.
- Editor approach A (embedded Gutenberg) — ADR 0001.
- Storage: H2 file default + PostgreSQL supported — ADR 0002.

## 11. Calls made by Claude (reviewable)

- (Superseded) multisite-in-one-process was Claude's early call; the
  author doesn't need it, so one install = one site (§6.4).
- YouTube embeds output as click-to-load facades (privacy, speed).
- Blocks of a disabled plugin render a plain fallback, publish does not
  fail.
- Store keeps Gutenberg block markup as the canonical post body.
- Java 25 LTS; Qute for the page shell and classic-style themes (block
  themes render through the block renderer); Quinoa with its own pinned Bun.
- **Non-post URLs kept**: category/tag/date/author archives with
  `/page/N/`, per-category and comment feeds, attachment pages (rendered or
  redirected to the parent post), `?p=<id>` shortlinks and other
  query-string or moved URLs via a **redirect map** held in Stillpress
  (reached directly in Mode 1, via nginx's not-found fallback in Mode 2 —
  see §6.2 item 9).
- **Search** (`?s=`) keeps working via a client-side static search index
  generated at publish time (no dynamic endpoint).
- **Custom blocks** in v1 come only from built-in plugins; theme- or
  site-supplied blocks are out of scope (consistent with "no plugin
  ecosystem").
- **Scheduled posts** supported (the app is always running, so it
  republishes at the scheduled time). **Private and draft** content is
  never published. **Password-protected posts**: not supported in v1;
  import flags them in the report.
- Podcast channel settings come from the export helper plugin; the live
  feed is used as a cross-check (and fallback when the plugin can't run).

## 12. Left to the builder

- Data model details (within jOOQ + Flyway + portable H2/Postgres schema).
- Exact WP REST shims beyond those listed (discover by logging).
- Incremental-publish dependency tracking design.
- Image pipeline specifics (sizes, formats, srcset).
- Admin information architecture within the ui-ux-pro-max guidance.
- Test strategy beyond the cutover gate (§12.1).
- Image pipeline: keep originals; responsive sizes and modern formats
  (WebP/AVIF with fallback) are the builder's call.
- Which further oEmbed providers to support: whatever the imported content
  actually uses (from the WXR inventory).

### 12.1 Cutover gate (decided)

A site may replace WordPress (DNS switch) only when a repeatable command,
run against the still-live WordPress, passes:

1. **Every URL resolves** — crawl the old site (sitemap, archives,
   pagination, feeds, attachment pages, `?p=` shortlinks); each returns 200
   on Stillpress or redirects to the same content.
2. **Looks the same** — desktop + mobile screenshots of every page/post on
   both sides; pixel diff under a small threshold, anything above is
   human-reviewed.
3. **Nothing missing** — item, comment, image and reusable-block counts
   match; the import report is empty or every line accepted by the author.
4. **Feeds match** — RSS item lists and GUIDs identical (plus podcast-feed
   parity, enclosure URLs and GUIDs, for it and newpodcast).
5. **Hands-on** — the author publishes a new post with a gallery and a
   YouTube embed, changes a colour in the Site Editor, approves a comment.

## 13. Spike evidence (throwaway, `spike/`)

Quarkus 3.40 + Quinoa 2.9.3 + Bun 1.4.3 hosting standalone Gutenberg
(`@wordpress/block-editor` 19). Verified: load → edit → save → reload with
zero invalid blocks; YouTube embed preview through a Java oEmbed proxy;
synced pattern create/reference/edit persisted via an emulated
`wp/v2/blocks` and reflected in a Java server render; custom block
registration. Found: apiFetch method override; dirty-entity saving is the
host's job; no built-in "edit original" screen; layout CSS missing without
global-styles generation; rendered-HTML import is lossy; Quinoa downloads
its own Bun; admin bundle ≈1.4 MB gzipped (+ lazy image worker).
The spike is not a foundation; nothing from it is kept without going
through the plan.
