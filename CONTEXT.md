# Stillpress

Stillpress is a self-hosted CMS that replaces WordPress: authors write in a Gutenberg block editor, and every site is published as static files that keep the source WordPress site's URLs and look.

## Language

### Sites and content

**Site**:
One published website with its own domains, admin, user accounts, content, media, theme, settings, plugins and output.
_Avoid_: blog (that's one specific site), host, tenant

**Install**:
One running Stillpress deployment with its own data directory; it hosts exactly one site.
_Avoid_: instance, server, network (WordPress multisite term)

**Setup wizard**:
The token-protected first-run flow at `/admin` that creates the site's first administrator and either starts empty or imports from WordPress.

**User**:
A person's account on exactly one site.
_Avoid_: member, author (that's a role)

**Item**:
Any addressable piece of content belonging to a site: a post, page, or episode.
_Avoid_: entry, document, node

**Post**:
A dated item that appears in feeds and archives.

**Page**:
An undated, standalone item (About, Contact) outside feeds and archives.

**Episode**:
An item of the podcasting plugin, carrying an audio file and appearing in a podcast feed.
_Avoid_: podcast (that's the whole show)

**Classic item**:
An item whose body is legacy HTML from WordPress's classic editor rather than block markup.
_Avoid_: legacy post, non-block post

**Reusable block**:
A block defined once per site and referenced from many items; editing it changes every item that uses it.
_Avoid_: synced pattern (WordPress's current UI name — use only when talking about WordPress itself), snippet

**Comment**:
A reader's response to a post, shown publicly only once approved.

**Moderation queue**:
The admin list of comments awaiting approval.

**Revision**:
A stored past version of an item, reusable block, template or style overrides that can be compared and restored.
_Avoid_: version, history entry

**Autosave**:
An unsaved-work snapshot taken while editing, offered for restore on reopen; not a revision.

### Appearance

**Theme**:
A packaged design (templates, template parts, styles, settings) a site uses to render its items.

**Style overrides**:
A site's visual changes on top of its theme's defaults, made in the Site Editor.
_Avoid_: customizations, global styles (WordPress's storage name)

**Admin**:
The dynamic management area of a site, at `/admin` on that site's own domain.
_Avoid_: dashboard, backend, wp-admin

**Site Editor**:
The admin screen where non-technical users tune a site's appearance visually.
_Avoid_: customizer

### Features

**Plugin**:
A built-in feature module (podcasting, galleries, SEO, …) that can be enabled or disabled per site. Not third-party code.
_Avoid_: extension, add-on, module

### Moving in and going out

**Import**:
The one-time act of bringing a WordPress site's content and configuration into a new site of the app; a site is imported at most once.
_Avoid_: sync, migration (use for the overall move)

**Import report**:
The list of everything an import could not map faithfully.

**Export helper**:
The small WordPress plugin shipped with this project that bundles settings WordPress's export and REST API don't expose.

**Publish**:
Making an item's current version public by writing the static files it affects into the site's output directory.

**Republish**:
Regenerating a whole site's output (after an appearance or settings change), swapped in atomically when complete.
_Avoid_: rebuild, redeploy

**Preview**:
A logged-in-only, on-the-fly rendering of an unpublished change; never written to the output directory.
_Avoid_: deploy, build, generate

**Output directory**:
The folder holding a site's published static files, next to the app.

**Redirect map**:
The per-site list of old URLs (shortlinks, query-string URLs, moved paths) and where they now resolve.

**Permalink**:
The public URL of an item, carried over unchanged from WordPress.
_Avoid_: slug (only the last path segment), path
