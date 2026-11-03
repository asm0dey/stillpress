# Stillpress 01 — Core Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a WordPress WXR export into Stillpress and publish it as a static site whose every URL matches the source WordPress site, served by Stillpress itself.

**Architecture:** One Quarkus app (one install = one site). WXR → importer → jOOQ/Flyway store (H2 file by default, PostgreSQL supported) → Java block parser/renderer → static publisher writing an atomic release into `data/output/current` → Vert.x static route serving it with a redirect-map fallback. A picocli front end exposes `serve`, `import`, `publish`, `verify-urls`. No admin UI, no themes engine yet (a minimal built-in "plain" Qute layout stands in).

**Tech Stack:** Java 25 (Liberica), Quarkus 3.40.1.1, Maven 3.9.16, mise, quarkus-picocli, quarkus-rest, quarkus-jooq 2.2.0 (jOOQ 3.20.3), Flyway, H2 2.x / PostgreSQL JDBC, Qute, jsoup 1.24.1, WireMock 3.13.2 (tests), dev.sivalabs testcontainers-jooq-codegen-maven-plugin 1.0.0 (Docker required for codegen).

**Spec:** `docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md` (+ `CONTEXT.md`, `docs/adr/0001-0003`)

**This is plan 1 of 5.** Later plans: 02 admin & editing, 03 themes & Site Editor, 04 blog plugins & blog cutover, 05 podcasting & it/newpodcast cutover.

## Global Constraints

- Java package root: `site.asm0dey.stillpress`. Licence: GPL-2.0-or-later (`LICENSE` file, SPDX headers not required).
- Toolchain via mise: `mise.toml` pins `java = "liberica-25.0.4+9"`, `maven = "3.9.16"`. All commands run as `mise exec -- <cmd>` or inside an activated mise shell.
- Data directory: config `stillpress.data-dir` (env `STILLPRESS_DATA_DIR`), default `./data`. Holds `stillpress.mv.db` (H2), `media/`, `output/`.
- Default datasource: `jdbc:h2:file:${stillpress.data-dir}/stillpress;AUTO_SERVER=FALSE`; PostgreSQL selected by setting `quarkus.datasource.db-kind=postgresql` + URL. jOOQ dialect follows the datasource.
- Flyway owns the schema; migrations in `src/main/resources/db/migration/common` (portable SQL only — no Postgres-only types, no partial/functional indexes, no arrays). jOOQ codegen runs against PostgreSQL via `-Pcodegen` into `target/generated-sources/jooq`, package `site.asm0dey.stillpress.db`.
- **Permalinks are byte-identical to the source.** Stored decoded (UTF-8, e.g. `/2023/01/14/месяц-ноябрь-год-2022/`), written to disk as UTF-8 directories ending in `index.html`, emitted in HTML/feeds percent-encoded with **lowercase** hex as WordPress does.
- **GUIDs are emitted verbatim** from WXR `<guid>`.
- Static blocks output their saved HTML unchanged; `wp-block-*` classes are kept (they are the CSS contract).
- Import is one-shot: importing into a store that already has items fails with `AlreadyImportedException`.
- Private, draft, pending and password-protected items are never published; password-protected items are listed in the import report.
- **Use the Quarkus Agent MCP (`quarkus-agent`) to drive the app during implementation**: start/stop dev mode, run tests, read logs and config, and call endpoints through it instead of ad-hoc shell loops. Fall back to `mvn` commands only where a step names one for CI reproducibility.
- Publishing is atomic: render into `output/releases/<yyyyMMdd-HHmmss-SSS>/`, then swap the `output/current` symlink; keep the 3 newest releases.

## Review Focus

1. **Percent-encoded vs. raw Cyrillic request paths** — a request for `/2023/01/14/%D0%BC...` (upper-case hex) and the raw UTF-8 path must both serve the same file. Test in Task 8.
2. **Trailing-slash and `index.html` variants** — `/about`, `/about/`, `/about/index.html` must behave as WordPress (redirect `/about` → `/about/`, 200 for the others). Test in Task 8.
3. **WXR with CDATA containing `]]>` splits and HTML entities in titles** — titles like `Q&amp;A` must render once-escaped, not double-escaped. Test in Task 3.
4. **Media URLs inside block attributes JSON, `srcset`, and classic `<a href>` wrappers** — all must be rewritten, not just `<img src>`. Test in Task 4.
5. **A crash mid-publish** — `output/current` must still point at the previous complete release. Test in Task 6.

---

## File Structure

```
mise.toml, pom.xml, LICENSE, .gitignore (append data/)
src/main/resources/application.properties
src/main/resources/db/migration/common/V1__core.sql
src/main/java/site/asm0dey/stillpress/
  StillpressCommand.java            picocli top command (serve|import|publish|verify-urls)
  config/DataDir.java               resolves/creates data dir subpaths
  blocks/Block.java                 parsed block record
  blocks/BlockParser.java           WP block grammar parser
  blocks/BlockRenderer.java         renders blocks; registry of dynamic renderers
  blocks/DynamicBlockRenderer.java  SPI interface
  blocks/ReusableBlockRenderer.java core/block
  blocks/EmbedRenderer.java         core/embed (YouTube facade, link fallback)
  blocks/ClassicContent.java        wpautop + [caption] for classic items
  wxr/Wxr*.java                     WXR records + WxrReader (StAX)
  importer/WordPressImporter.java   WXR → store, report
  importer/MediaFetcher.java        interface + HttpMediaFetcher
  importer/MediaRewriter.java       source upload URLs → /media/... in HTML & block JSON
  importer/ImportReport.java
  store/*Repository.java            jOOQ repositories (items, terms, users, comments, media, reusable blocks, redirects, settings)
  publish/Permalinks.java           encode/decode helpers
  publish/SitePublisher.java        publishAll(): pages, archives, feeds, 404, robots
  publish/ReleaseSwitcher.java      atomic release + symlink swap
  publish/views/*.java              view models for templates
  serve/StaticSiteRoute.java        Vert.x route: files + redirect fallback
  verify/UrlParityChecker.java      cutover check #1
src/main/resources/templates/plain/{base,single,archive,404}.html
src/main/resources/templates/feed/{rss2.xml,comments-rss2.xml}
src/test/resources/wxr/sample-blog.xml
src/test/java/site/asm0dey/stillpress/... (mirrors main)
```

---

### Task 1: Project skeleton, storage schema, CLI shell

**Files:**
- Create: `mise.toml`, `pom.xml`, `LICENSE` (GPL-2.0 text), `src/main/resources/application.properties`, `src/main/resources/db/migration/common/V1__core.sql`, `src/main/java/site/asm0dey/stillpress/StillpressCommand.java`, `src/main/java/site/asm0dey/stillpress/config/DataDir.java`
- Modify: `.gitignore` (add `data/`)
- Test: `src/test/java/site/asm0dey/stillpress/config/DataDirTest.java`, `src/test/java/site/asm0dey/stillpress/store/SchemaTest.java`

**Interfaces:**
- Produces: `DataDir` (`@ApplicationScoped`) with `Path root()`, `Path media()`, `Path output()`, `Path releases()`, `Path current()` — creates directories on first access. jOOQ `DSLContext` injectable. Generated tables in `site.asm0dey.stillpress.db.tables.*`: `ITEM`, `TERM`, `ITEM_TERM`, `AUTHOR`, `REUSABLE_BLOCK`, `COMMENT`, `MEDIA`, `REDIRECT`, `SETTING`. `StillpressCommand` with subcommands registered by later tasks.

- [ ] **Step 1: Write `mise.toml` and `pom.xml`**

`mise.toml`: `[tools] java = "liberica-25.0.4+9"`, `maven = "3.9.16"`. `pom.xml`: Quarkus platform BOM `3.40.1.1`; deps `quarkus-picocli`, `quarkus-rest`, `quarkus-qute`, `quarkus-flyway`, `quarkus-jdbc-h2`, `quarkus-jdbc-postgresql`, `io.quarkiverse.jooq:quarkus-jooq:2.2.0`, `org.jsoup:jsoup:1.24.1`; test deps `quarkus-junit`, `org.wiremock:wiremock-standalone:3.13.2`, `org.assertj:assertj-core`. `build-helper-maven-plugin` adds `target/generated-sources/jooq`. Profile `codegen` with `dev.sivalabs:testcontainers-jooq-codegen-maven-plugin:1.0.0` (Postgres container, Flyway locations `filesystem:src/main/resources/db/migration/common`, jOOQ `3.20.3` pinned in plugin deps, target package `site.asm0dey.stillpress.db`) — copy the shape from `~/work_self/slidev-polls/pom.xml` profile `codegen`.

- [ ] **Step 2: Write `V1__core.sql`** (portable)

Tables (all `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` unless stated):
- `setting(key VARCHAR(100) PRIMARY KEY, value TEXT)`
- `author(id, login VARCHAR(60) UNIQUE NOT NULL, display_name VARCHAR(250), email VARCHAR(100), slug VARCHAR(200) UNIQUE NOT NULL, source_id BIGINT)`
- `item(id, source_id BIGINT UNIQUE, type VARCHAR(20) NOT NULL /*post|page|attachment*/, status VARCHAR(20) NOT NULL /*publish|draft|pending|private|future*/, password_protected BOOLEAN NOT NULL DEFAULT FALSE, title TEXT, slug VARCHAR(200), permalink VARCHAR(2000) UNIQUE /*decoded path*/, body TEXT, body_format VARCHAR(10) NOT NULL /*blocks|classic*/, excerpt TEXT, author_id BIGINT REFERENCES author(id), parent_id BIGINT REFERENCES item(id), published_at TIMESTAMP, modified_at TIMESTAMP, guid VARCHAR(2000), comment_status VARCHAR(10), menu_order INT DEFAULT 0, mime_type VARCHAR(100))`
- `term(id, source_id BIGINT, taxonomy VARCHAR(32) NOT NULL /*category|post_tag*/, name VARCHAR(200) NOT NULL, slug VARCHAR(200) NOT NULL, parent_id BIGINT REFERENCES term(id), description TEXT, UNIQUE(taxonomy, slug))`
- `item_term(item_id BIGINT REFERENCES item(id), term_id BIGINT REFERENCES term(id), PRIMARY KEY(item_id, term_id))`
- `reusable_block(id, source_id BIGINT UNIQUE, title TEXT, content TEXT NOT NULL)`
- `comment(id, source_id BIGINT UNIQUE, item_id BIGINT NOT NULL REFERENCES item(id), parent_id BIGINT REFERENCES comment(id), author_name VARCHAR(250), author_email VARCHAR(100), author_url VARCHAR(200), content TEXT NOT NULL, created_at TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL /*approved|hold|spam|trash*/)`
- `media(id, item_id BIGINT REFERENCES item(id) /*attachment item*/, source_url VARCHAR(2000) UNIQUE NOT NULL, path VARCHAR(1000) /*relative to data/media; null if missing*/, mime_type VARCHAR(100), sha256 VARCHAR(64))`
- `redirect(from_path VARCHAR(2000) PRIMARY KEY /*decoded path, or "?p=123"-style key*/, to_path VARCHAR(2000) NOT NULL, status_code INT NOT NULL DEFAULT 301)`

- [ ] **Step 3: Write `application.properties`**

`stillpress.data-dir=${STILLPRESS_DATA_DIR:./data}`, H2 URL from Global Constraints, `quarkus.flyway.migrate-at-start=true`, `quarkus.flyway.locations=db/migration/common`, `quarkus.http.port=8080`, test profile `%test.stillpress.data-dir=target/test-data/${quarkus.uuid}`.

- [ ] **Step 4: Write failing tests**

```java
// DataDirTest
@QuarkusTest class DataDirTest {
  @Inject DataDir dataDir;
  @Test void createsSubdirectories() {
    assertThat(dataDir.media()).isDirectory().startsWith(dataDir.root());
    assertThat(dataDir.releases()).isDirectory();
    assertThat(dataDir.current()).isEqualTo(dataDir.output().resolve("current"));
  }
}
// SchemaTest
@QuarkusTest class SchemaTest {
  @Inject DSLContext db;
  @Test void migrationsCreateCoreTables() {
    db.insertInto(SETTING, SETTING.KEY, SETTING.VALUE).values("title", "Блог").execute();
    assertThat(db.fetchValue(SETTING.VALUE, SETTING.KEY.eq("title"))).isEqualTo("Блог");
  }
}
```

- [ ] **Step 5: Run codegen, verify tests fail**

Run: `mise exec -- mvn -q -Pcodegen generate-sources && mise exec -- mvn -q test -Dtest='DataDirTest,SchemaTest'`
Expected: codegen succeeds (Docker running); tests FAIL — `DataDir` missing.

- [ ] **Step 6: Implement `DataDir` and an empty `StillpressCommand`**

`StillpressCommand`: `@TopCommand @CommandLine.Command(name = "stillpress", mixinStandardHelpOptions = true, subcommands = {})`; a `serve` subcommand class `ServeCommand implements Runnable` that calls `Quarkus.waitForExit()` and is the default when no args (`quarkus.picocli.top-command` + `defaultValueProvider` not needed — implement `Runnable` on top command delegating to serve).

- [ ] **Step 7: Run tests, verify pass**

Run: `mise exec -- mvn -q test -Dtest='DataDirTest,SchemaTest'` → PASS

- [ ] **Step 8: Commit**

```bash
git add mise.toml pom.xml LICENSE .gitignore src
git commit -m "feat: project skeleton, portable schema, CLI shell"
```

---

### Task 2: Block parser

**Files:**
- Create: `src/main/java/site/asm0dey/stillpress/blocks/Block.java`, `blocks/BlockParser.java`
- Test: `src/test/java/site/asm0dey/stillpress/blocks/BlockParserTest.java`

**Interfaces:**
- Produces:
  - `record Block(String name, JsonNode attrs, List<Block> innerBlocks, List<String> innerContent)` — `name` is fully qualified (`core/paragraph`); `null` name = freeform HTML; `innerContent` interleaves HTML strings with `null` placeholders, one per inner block (WordPress's own representation). Helper `String innerHtml()` = concatenation of non-null `innerContent`.
  - `final class BlockParser { static List<Block> parse(String document) }` — plain static, no CDI.

- [ ] **Step 1: Write failing tests**

```java
@Test void namespacelessNameIsCore() {
  var b = BlockParser.parse("<!-- wp:paragraph -->\n<p>Привет</p>\n<!-- /wp:paragraph -->");
  assertThat(b).singleElement().satisfies(x -> {
    assertThat(x.name()).isEqualTo("core/paragraph");
    assertThat(x.innerHtml()).isEqualTo("\n<p>Привет</p>\n");
  });
}
@Test void voidBlockWithAttributes() {
  var b = BlockParser.parse("<!-- wp:block {\"ref\":42} /-->").getFirst();
  assertThat(b.name()).isEqualTo("core/block");
  assertThat(b.attrs().get("ref").asLong()).isEqualTo(42);
  assertThat(b.innerContent()).isEmpty();
}
@Test void nestedGalleryKeepsPlaceholders() {
  var doc = "<!-- wp:gallery {\"linkTo\":\"none\"} --><figure class=\"wp-block-gallery\">"
    + "<!-- wp:image {\"id\":1} --><figure><img src=\"a.jpg\"/></figure><!-- /wp:image -->"
    + "<!-- wp:image {\"id\":2} --><figure><img src=\"b.jpg\"/></figure><!-- /wp:image -->"
    + "</figure><!-- /wp:gallery -->";
  var g = BlockParser.parse(doc).getFirst();
  assertThat(g.innerBlocks()).extracting(Block::name).containsExactly("core/image", "core/image");
  assertThat(g.innerContent()).containsExactly("<figure class=\"wp-block-gallery\">", null, null, "</figure>");
}
@Test void freeformBetweenBlocks() {
  var b = BlockParser.parse("<p>loose</p><!-- wp:separator /-->");
  assertThat(b).extracting(Block::name).containsExactly(null, "core/separator");
  assertThat(b.getFirst().innerHtml()).isEqualTo("<p>loose</p>");
}
@Test void namespacedThirdPartyBlock() {
  assertThat(BlockParser.parse("<!-- wp:jetpack/tiled-gallery {\"columnWidths\":[[50,50]]} --><div></div><!-- /wp:jetpack/tiled-gallery -->")
    .getFirst().name()).isEqualTo("jetpack/tiled-gallery");
}
@Test void unclosedBlockBecomesFreeformNotException() {
  assertThat(BlockParser.parse("<!-- wp:paragraph --><p>x</p>")).isNotEmpty();
}
```

- [ ] **Step 2: Run tests, verify fail** — `mise exec -- mvn -q test -Dtest=BlockParserTest` → FAIL (classes missing).

- [ ] **Step 3: Implement `BlockParser.parse`**

Port of WordPress's `WP_Block_Parser` (`wp-includes/class-wp-block-parser.php`) token loop: delimiter regex `<!--\s+(?<closer>/)?wp:(?<namespace>[a-z][a-z0-9_-]*/)?(?<name>[a-z][a-z0-9_-]*)\s+(?<attrs>\{(?:(?:[^}]+|}+(?=})|(?!}\s+/?-->).)*)?}\s+)?(?<void>/)?-->` (DOTALL), stack of open frames, freeform for top-level text between blocks; malformed/unclosed input never throws — remaining text becomes freeform. Attributes parsed with Jackson; invalid JSON → empty object.

- [ ] **Step 4: Run tests, verify pass** — → PASS

- [ ] **Step 5: Commit** — `git commit -m "feat: WordPress block grammar parser"`

---

### Task 3: Block renderer, classic content, WXR reader

**Files:**
- Create: `blocks/DynamicBlockRenderer.java`, `blocks/RenderContext.java`, `blocks/BlockRenderer.java`, `blocks/ReusableBlockRenderer.java`, `blocks/EmbedRenderer.java`, `blocks/ClassicContent.java`, `wxr/WxrDocument.java`, `wxr/WxrSite.java`, `wxr/WxrAuthor.java`, `wxr/WxrTerm.java`, `wxr/WxrItem.java`, `wxr/WxrComment.java`, `wxr/WxrReader.java`, `src/test/resources/wxr/sample-blog.xml`
- Test: `blocks/BlockRendererTest.java`, `blocks/ClassicContentTest.java`, `wxr/WxrReaderTest.java`

**Interfaces:**
- Consumes: `Block`, `BlockParser` (Task 2).
- Produces:
  - `interface DynamicBlockRenderer { String blockName(); String render(Block block, String renderedInner, RenderContext ctx); }` — CDI beans, collected by `BlockRenderer`.
  - `record RenderContext(Function<Long, Optional<String>> reusableBlockContent, int depth)`; `RenderContext nested()` returns depth+1.
  - `@ApplicationScoped class BlockRenderer { String render(String document, RenderContext ctx); String render(List<Block> blocks, RenderContext ctx); }` — static blocks: `innerContent` with placeholders replaced by rendered inner blocks; freeform: as-is; dynamic: delegate. Depth > 5 → empty string (reusable recursion guard).
  - `ReusableBlockRenderer` (`core/block`): renders referenced content via `ctx.reusableBlockContent()`; missing ref → empty.
  - `EmbedRenderer` (`core/embed`): YouTube URL (`v=`, `youtu.be/`, `shorts/`, `embed/`) → click-to-load facade (exact markup below); any other URL → `<figure class="wp-block-embed"><a href="URL">URL</a></figure>` (provider list grows in plan 04).
  - `final class ClassicContent { static String toHtml(String classicHtml) }` — port of WordPress `wpautop()` plus `[caption]` → `<figure class="wp-caption">…<figcaption class="wp-caption-text">…</figcaption></figure>`; unknown shortcodes left verbatim.
  - `final class WxrReader { static WxrDocument read(InputStream in) }` (StAX, streaming, namespaces `wp`, `content`, `excerpt`, `dc`).
  - `record WxrDocument(WxrSite site, List<WxrAuthor> authors, List<WxrTerm> terms, List<WxrItem> items)`; `record WxrSite(String title, String link, String description, String language, String baseSiteUrl, String baseBlogUrl)`; `record WxrAuthor(long id, String login, String email, String displayName)`; `record WxrTerm(long id, String taxonomy, String slug, String name, String parentSlug, String description)`; `record WxrItem(long postId, String postType, String status, String title, String link, String guid, String postName, String content, String excerpt, Instant postDateGmt, Instant modifiedGmt, String creator, long parentId, int menuOrder, String password, String commentStatus, String attachmentUrl, List<WxrTermRef> terms, Map<String,String> meta, List<WxrComment> comments)`; `record WxrTermRef(String domain, String nicename)`; `record WxrComment(long id, long parentId, String author, String email, String url, Instant dateGmt, String content, String approved)`.

YouTube facade markup (exact):
```html
<figure class="wp-block-embed is-type-video is-provider-youtube wp-block-embed-youtube"><div class="wp-block-embed__wrapper"><button type="button" class="sp-yt-facade" data-yt-id="{ID}" style="background-image:url(https://i.ytimg.com/vi/{ID}/hqdefault.jpg)" aria-label="Play video"></button></div></figure>
```
(Plain template ships a 10-line script swapping the button for an `https://www.youtube-nocookie.com/embed/{ID}?autoplay=1` iframe on click.)

- [ ] **Step 1: Write the WXR fixture** `src/test/resources/wxr/sample-blog.xml` — WXR 1.2 with: site title `Блог`, language `ru-RU`, base URL `https://blog.example.ru`; authors `mama` and `papa`; categories `travel` (name `Путешествия`) and child `europe`; tag `kids`; items:
  1. post 5994, status publish, title `Месяц ноябрь, год 2022`, link `https://blog.example.ru/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/`, guid `https://blog.example.ru/?p=5994`, block content with a `core/gallery` of two `core/image` whose `src`, `srcset` and `{"id":…,"url":…}` attrs point at `https://blog.example.ru/wp-content/uploads/2023/01/a.jpg`, a `core/block {"ref":6001}`, a YouTube `core/embed`; categories travel+europe, tag kids; two comments (one reply to the other, both approved) + one `spam` comment.
  2. post 100, publish, classic content `Line one\n\nLine two [caption id="attachment_7" width="300"]<img src="https://blog.example.ru/wp-content/uploads/2020/05/c.jpg" /> Подпись[/caption]`, title `Q&amp;A`, link `https://blog.example.ru/2020/05/10/qa/`.
  3. page 2, publish, link `https://blog.example.ru/about/`, title `О нас`.
  4. post 101, status draft. 5. post 102, publish, link `https://blog.example.ru/2021/03/01/secret-post/`, with `<wp:post_password>secret</wp:post_password>`. Post 101 (draft) has link `https://blog.example.ru/?p=101` and post_name `draft-post`.
  6. attachment 7, parent 100, `wp:attachment_url` `https://blog.example.ru/wp-content/uploads/2020/05/c.jpg`, link `https://blog.example.ru/2020/05/10/qa/c/`.
  7. `wp_block` 6001 content `<!-- wp:paragraph --><p>Подпишитесь!</p><!-- /wp:paragraph -->`.
  8. `nav_menu_item` 9000 (must be ignored in this plan).
  Titles/content in CDATA; one content contains a literal `]]>` split as `]]]]><![CDATA[>`.

- [ ] **Step 2: Write failing tests**

```java
// WxrReaderTest
@Test void readsSiteAuthorsTermsAndItems() {
  var d = WxrReader.read(res("wxr/sample-blog.xml"));
  assertThat(d.site().title()).isEqualTo("Блог");
  assertThat(d.authors()).extracting(WxrAuthor::login).containsExactly("mama", "papa");
  assertThat(d.terms()).extracting(WxrTerm::slug).contains("travel", "europe", "kids");
  var p = d.items().stream().filter(i -> i.postId() == 5994).findFirst().orElseThrow();
  assertThat(p.comments()).hasSize(3);
  assertThat(p.guid()).isEqualTo("https://blog.example.ru/?p=5994");
}
@Test void titleEntitiesDecodedOnce() {
  assertThat(item(100).title()).isEqualTo("Q&A");
}
@Test void cdataSplitIsJoined() { assertThat(anyContentContains("]]>")).isTrue(); }
@Test void passwordAndAttachmentUrlRead() {
  assertThat(item(102).password()).isEqualTo("secret");
  assertThat(item(7).attachmentUrl()).endsWith("/2020/05/c.jpg");
}

// BlockRendererTest (@QuarkusTest)
@Test void staticBlocksRenderSavedHtml() {
  var html = renderer.render("<!-- wp:gallery --><figure class=\"wp-block-gallery\"><!-- wp:image --><figure class=\"wp-block-image\"><img src=\"a.jpg\"/></figure><!-- /wp:image --></figure><!-- /wp:gallery -->", ctx());
  assertThat(html).isEqualTo("<figure class=\"wp-block-gallery\"><figure class=\"wp-block-image\"><img src=\"a.jpg\"/></figure></figure>");
}
@Test void reusableBlockResolvedRecursivelyWithGuard() {
  var ctx = new RenderContext(id -> id == 1 ? Optional.of("<!-- wp:block {\"ref\":1} /-->") : Optional.empty(), 0);
  assertThat(renderer.render("<!-- wp:block {\"ref\":1} /-->", ctx)).isEmpty();
}
@Test void youtubeEmbedBecomesFacade() {
  var html = renderer.render("<!-- wp:embed {\"url\":\"https://youtu.be/dQw4w9WgXcQ\",\"providerNameSlug\":\"youtube\"} --><figure class=\"wp-block-embed\"><div class=\"wp-block-embed__wrapper\">\nhttps://youtu.be/dQw4w9WgXcQ\n</div></figure><!-- /wp:embed -->", ctx());
  assertThat(html).contains("data-yt-id=\"dQw4w9WgXcQ\"").doesNotContain("<iframe");
}

// ClassicContentTest
@Test void autopWrapsParagraphs() {
  assertThat(ClassicContent.toHtml("Line one\n\nLine two")).isEqualTo("<p>Line one</p>\n<p>Line two</p>\n");
}
@Test void captionShortcodeBecomesFigure() {
  assertThat(ClassicContent.toHtml("[caption id=\"attachment_7\" width=\"300\"]<img src=\"c.jpg\" /> Подпись[/caption]"))
    .contains("<figure").contains("<figcaption class=\"wp-caption-text\">Подпись</figcaption>");
}
@Test void unknownShortcodeKept() { assertThat(ClassicContent.toHtml("[gallery ids=\"1,2\"]")).contains("[gallery ids=\"1,2\"]"); }
```

- [ ] **Step 3: Run tests, verify fail** — `mise exec -- mvn -q test -Dtest='WxrReaderTest,BlockRendererTest,ClassicContentTest'` → FAIL.

- [ ] **Step 4: Implement** the types above. `wpautop` is a straight port of `wp-includes/formatting.php` `wpautop()` (block-level tag list, `<pre>` preservation, `br=true`). `[gallery]` handling is plan 04 (galleries plugin).

- [ ] **Step 5: Run tests, verify pass** → PASS

- [ ] **Step 6: Commit** — `git commit -m "feat: block renderer, classic content, WXR reader"`

---

### Task 4: Importer with media download and URL rewriting

**Files:**
- Create: `importer/WordPressImporter.java`, `importer/ImportReport.java`, `importer/AlreadyImportedException.java`, `importer/MediaFetcher.java`, `importer/HttpMediaFetcher.java`, `importer/MediaRewriter.java`, `importer/ImportCommand.java`, `store/ItemRepository.java`, `store/TermRepository.java`, `store/AuthorRepository.java`, `store/CommentRepository.java`, `store/MediaRepository.java`, `store/ReusableBlockRepository.java`, `store/RedirectRepository.java`, `store/SettingRepository.java`, `publish/Permalinks.java`
- Test: `importer/WordPressImporterTest.java`, `importer/MediaRewriterTest.java`, `publish/PermalinksTest.java`

**Interfaces:**
- Consumes: `WxrReader`/`Wxr*` (Task 3), `DataDir`, jOOQ tables (Task 1), `BlockParser` (Task 2).
- Produces:
  - `final class Permalinks { static String decodedPath(String absoluteUrl); static String encode(String decodedPath) /* lowercase %xx, keeps '/' */; static Path toFile(Path root, String decodedPath) /* .../index.html */ }`
  - `interface MediaFetcher { Optional<byte[]> fetch(URI source); }` — `HttpMediaFetcher` default bean (java.net.http, 30 s timeout, follows redirects); tests replace it with an `@Alternative`/mock returning fixture bytes.
  - `final class MediaRewriter { MediaRewriter(Map<String,String> sourceToLocal); String rewrite(String html) }` — replaces every occurrence of each source URL (and its `-WxH` size variants `name-300x200.jpg` mapped to the same local file) in raw text, which covers `src`, `href`, `srcset` and block-comment JSON alike. Local URL form: `/media/<yyyy>/<mm>/<sha256-first-12>-<original-filename>`.
  - `@ApplicationScoped class WordPressImporter { ImportReport importWxr(WxrDocument doc) throws AlreadyImportedException }` — single transaction for DB rows; media files written before commit; on exception the transaction rolls back.
  - `record ImportReport(int items, int comments, int media, int reusableBlocks, List<String> unknownBlocks, List<String> unknownShortcodes, List<String> missingMedia, List<String> passwordProtected, List<String> skippedPostTypes)` with `String toText()`.
  - Settings written: `title`, `description`, `language`, `home_url` (from `WxrSite.link`), `posts_per_page`=`10`, `category_base`=`category`, `tag_base`=`tag`.
  - Redirects written: `?p=<id>` → permalink for every imported post/page; `?page_id=<id>` → page permalink; each attachment's decoded permalink → parent's permalink (301); `?attachment_id=<id>` → parent permalink.
  - Repositories: thin jOOQ classes; method names `ItemRepository.findPublished()`, `findByPermalink(String)`, `count()`, `ReusableBlockRepository.content(long id) -> Optional<String>`, `RedirectRepository.find(String key) -> Optional<RedirectRecord>`, `CommentRepository.approvedFor(long itemId)`, `TermRepository.forItem(long itemId)`, `SettingRepository.get(String)/put(String,String)`.
  - CLI: `stillpress import --wxr <file>` prints `ImportReport.toText()`; exit 1 on `AlreadyImportedException`.
- Known block names (not reported as unknown): every `core/*`, plus `jetpack/tiled-gallery`, `panorama360/flat-image` (renderers come in plan 04). Unknown shortcodes = any `[name ...]` in classic content other than `caption`, `gallery`, `embed`, `video`, `audio`.

- [ ] **Step 1: Write failing tests**

```java
// PermalinksTest
@Test void decodesAndReencodesLowercase() {
  var p = Permalinks.decodedPath("https://blog.example.ru/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86/");
  assertThat(p).isEqualTo("/2023/01/14/месяц/");
  assertThat(Permalinks.encode(p)).isEqualTo("/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86/");
}
// MediaRewriterTest
@Test void rewritesSrcSrcsetJsonAndSizeVariants() {
  var r = new MediaRewriter(Map.of("https://b.ru/wp-content/uploads/2023/01/a.jpg", "/media/2023/01/abc123-a.jpg"));
  var out = r.rewrite("<!-- wp:image {\"url\":\"https://b.ru/wp-content/uploads/2023/01/a.jpg\"} --><img src=\"https://b.ru/wp-content/uploads/2023/01/a-300x200.jpg\" srcset=\"https://b.ru/wp-content/uploads/2023/01/a.jpg 1024w\"/>");
  assertThat(out).doesNotContain("wp-content/uploads").contains("/media/2023/01/abc123-a.jpg");
}
// WordPressImporterTest (@QuarkusTest, MediaFetcher mocked to return 3 bytes per URL)
@Test void importsSampleBlog() {
  var report = importer.importWxr(WxrReader.read(res("wxr/sample-blog.xml")));
  assertThat(report.items()).isEqualTo(6);           // 5994,100,2,101,102,7
  assertThat(report.comments()).isEqualTo(3);
  assertThat(report.reusableBlocks()).isEqualTo(1);
  assertThat(report.passwordProtected()).containsExactly("/2021/03/01/secret-post/");
  assertThat(report.skippedPostTypes()).contains("nav_menu_item");
  assertThat(items.findByPermalink("/2023/01/14/месяц-ноябрь-год-2022/")).isPresent();
  assertThat(items.findByPermalink("/2023/01/14/месяц-ноябрь-год-2022/").orElseThrow().getBody()).doesNotContain("wp-content/uploads");
  assertThat(redirects.find("?p=5994").orElseThrow().getToPath()).isEqualTo("/2023/01/14/месяц-ноябрь-год-2022/");
  assertThat(redirects.find("/2020/05/10/qa/c/").orElseThrow().getToPath()).isEqualTo("/2020/05/10/qa/");
  assertThat(Files.list(dataDir.media().resolve("2023/01"))).isNotEmpty();
}
@Test void secondImportRefused() {
  importer.importWxr(sample());
  assertThatThrownBy(() -> importer.importWxr(sample())).isInstanceOf(AlreadyImportedException.class);
}
@Test void missingMediaReportedNotFatal() { /* fetcher returns empty for c.jpg */
  assertThat(importer.importWxr(sample()).missingMedia()).anyMatch(u -> u.endsWith("/2020/05/c.jpg"));
}
```

- [ ] **Step 2: Run tests, verify fail** — `mise exec -- mvn -q test -Dtest='PermalinksTest,MediaRewriterTest,WordPressImporterTest'` → FAIL.

- [ ] **Step 3: Implement** the types above. Item `body_format` = `blocks` when content contains `<!-- wp:`, else `classic`. Comments: `approved` 1→`approved`, 0→`hold`, `spam`/`trash` kept as is. Author ↔ `dc:creator` login. Terms: parents resolved by slug after insert. Each test method uses a fresh data dir (test profile) — add `@TestProfile` or truncate tables in `@BeforeEach` via `DSLContext`.

- [ ] **Step 4: Run tests, verify pass** → PASS

- [ ] **Step 5: Commit** — `git commit -m "feat: one-shot WXR importer with media and redirects"`

---

### Task 5: Plain layout templates and view models

**Files:**
- Create: `publish/views/ItemView.java`, `publish/views/ArchiveView.java`, `publish/views/SiteView.java`, `publish/views/CommentView.java`, `src/main/resources/templates/plain/base.html`, `plain/single.html`, `plain/archive.html`, `plain/404.html`, `src/main/resources/templates/feed/rss2.xml`, `feed/comments-rss2.xml`
- Test: `publish/TemplatesTest.java`

**Interfaces:**
- Consumes: repositories (Task 4), `BlockRenderer`, `ClassicContent` (Task 3), `Permalinks`.
- Produces (records, all strings already HTML-safe where noted):
  - `record SiteView(String title, String description, String language /*e.g. ru-RU → lang "ru"*/, String homeUrl)`
  - `record ItemView(long id, String title, String permalinkEncoded, String absoluteUrl, String contentHtml /*rendered*/, String excerptHtml, Instant publishedAt, String authorName, String authorUrl, List<TermLink> categories, List<TermLink> tags, List<CommentView> comments, String guid)`; `record TermLink(String name, String url)`
  - `record CommentView(long id, String authorName, String authorUrl, Instant createdAt, String contentHtml, List<CommentView> replies)`
  - `record ArchiveView(String heading, List<ItemView> items, int page, int totalPages, String prevUrl, String nextUrl)`
  - `@ApplicationScoped class ViewFactory { ItemView item(ItemRecord r); SiteView site(); }` — `contentHtml` = `BlockRenderer.render` for `blocks`, `ClassicContent.toHtml` for `classic`; comments threaded, only `approved`.
  - Qute templates `plain/single`, `plain/archive`, `plain/404` (each `{#include plain/base}`), `feed/rss2` (RSS 2.0 like WordPress: `<guid isPermaLink="false">{guid}</guid>`, `<content:encoded><![CDATA[...]]></content:encoded>`, `pubDate` RFC-1123 GMT, `<atom:link rel="self">`), `feed/comments-rss2`.
  - `plain/base.html` links `/assets/plain.css` (minimal readable CSS) and `/assets/block-library.css` (copied from `@wordpress/block-library` `build-style/style.css` v12.0.0, committed under `src/main/resources/static-assets/`), and the YouTube facade script.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class TemplatesTest {
  @Test void singleRendersTitleOnceEscapedAndLang() {
    var html = render("plain/single", site(), item("Q&A", "<p>x</p>"));
    assertThat(html).contains("<html lang=\"ru\"").contains("<h1>Q&amp;A</h1>").doesNotContain("&amp;amp;");
  }
  @Test void commentsThreaded() {
    var html = render("plain/single", site(), itemWithReplyChain());
    assertThat(Jsoup.parse(html).select("li.comment li.comment")).hasSize(1);
  }
  @Test void rssUsesVerbatimGuid() {
    var xml = renderFeed(List.of(item("T", "<p>x</p>").withGuid("https://blog.example.ru/?p=5994")));
    assertThat(xml).contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994</guid>");
  }
}
```

- [ ] **Step 2: Run, verify fail** → FAIL. **Step 3: Implement.** **Step 4: Run, verify pass** → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: plain layout, view models, RSS templates"`

---

### Task 6: Site publisher with atomic releases

**Files:**
- Create: `publish/SitePublisher.java`, `publish/PublishResult.java`, `publish/ReleaseSwitcher.java`, `publish/PublishCommand.java`
- Test: `publish/SitePublisherTest.java`, `publish/ReleaseSwitcherTest.java`

**Interfaces:**
- Consumes: Tasks 3–5.
- Produces:
  - `@ApplicationScoped class ReleaseSwitcher { Path newRelease(); void activate(Path release) /* atomic symlink swap: create current.tmp → Files.move(ATOMIC_MOVE, REPLACE_EXISTING); prune to 3 newest */ }`
  - `@ApplicationScoped class SitePublisher { PublishResult publishAll() }`; `record PublishResult(Path release, int files, Duration took)`.
  - Output layout (decoded paths, each `…/index.html` unless a file name is given):
    - every published, non-password `post`/`page` at its permalink; item comment feed at `<permalink>feed/index.html` (RSS)
    - home `/` and `/page/N/` (posts only, newest first, `posts_per_page`)
    - `/<category_base>/<slug>/[page/N/]` incl. child categories (`/category/travel/europe/`), `/<tag_base>/<slug>/[page/N/]`, `/author/<slug>/[page/N/]`, `/YYYY/`, `/YYYY/MM/`, `/YYYY/MM/DD/` (each with `page/N/`)
    - feeds: `/feed/index.html` (10 newest), `/comments/feed/`, `/<category_base>/<slug>/feed/`, `/<tag_base>/<slug>/feed/`, `/author/<slug>/feed/`
    - `/404.html`, `/robots.txt` (`User-agent: *\nDisallow: /admin/\n`), `/assets/*` copied from `static-assets/`, `/media/**` hard-linked (fallback copy) from `data/media`
  - Feed files are written as `index.html` in their directory but must be served as `application/rss+xml` — `StaticSiteRoute` (Task 7) maps any path ending in `/feed/` to that type; record that contract in a constant `SitePublisher.FEED_SEGMENT = "feed"`.
  - CLI: `stillpress publish` prints file count and release path.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class SitePublisherTest {
  @BeforeEach void importSample() { /* truncate + import sample-blog.xml with fake fetcher */ }
  @Test void writesItemsAtDecodedPermalinks() {
    var r = publisher.publishAll();
    assertThat(r.release().resolve("2023/01/14/месяц-ноябрь-год-2022/index.html")).exists();
    assertThat(r.release().resolve("about/index.html")).exists();
  }
  @Test void draftsAndPasswordProtectedNotPublished() {
    var r = publisher.publishAll();
    assertThat(Files.walk(r.release()).map(Path::toString)).noneMatch(p -> p.contains("secret-post") || p.contains("draft-post"));
  }
  @Test void archivesAndFeeds() {
    var rel = publisher.publishAll().release();
    assertThat(rel.resolve("category/travel/europe/index.html")).exists();
    assertThat(rel.resolve("tag/kids/index.html")).exists();
    assertThat(rel.resolve("2023/01/index.html")).exists();
    assertThat(rel.resolve("author/mama/index.html")).exists();
    assertThat(rel.resolve("feed/index.html")).content().contains("<guid isPermaLink=\"false\">https://blog.example.ru/?p=5994</guid>");
  }
  @Test void currentPointsAtNewRelease() {
    var rel = publisher.publishAll().release();
    assertThat(dataDir.current().toRealPath()).isEqualTo(rel.toRealPath());
  }
}
class ReleaseSwitcherTest {
  @Test void crashBeforeActivateKeepsPreviousCurrent() { /* activate(r1); newRelease() r2 half-written, no activate → current still r1 */ }
  @Test void keepsThreeNewest() { /* activate 5 releases → 3 remain, current = newest */ }
}
```

- [ ] **Step 2: Run, verify fail** → FAIL. **Step 3: Implement.** **Step 4: Run, verify pass** → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: atomic static site publisher"`

---

### Task 7: Serving Mode 1 with redirect fallback

**Files:**
- Create: `serve/StaticSiteRoute.java`
- Test: `serve/StaticSiteRouteTest.java`

**Interfaces:**
- Consumes: `DataDir.current()`, `RedirectRepository`, `Permalinks`, `SitePublisher.FEED_SEGMENT`.
- Produces: a Vert.x route (`@Route(regex = ".*", order = 1000)` or `Router` observer) — lowest priority so later plans' `/admin` etc. win. Behaviour, in order:
  1. Query keys `p`, `page_id`, `attachment_id` present → look up redirect key `?<k>=<v>` → 301 to encoded target; unknown → 404 page.
  2. Decode path (percent-decoding is case-insensitive; reject `..` segments → 400).
  3. Path maps to a regular file under `current` → serve it (content type by extension; `…/feed/` dirs → `application/rss+xml; charset=UTF-8`; `.html` → `text/html; charset=UTF-8`); `Cache-Control: public, max-age=300` for HTML/feeds, `max-age=31536000, immutable` for `/media/` and `/assets/`.
  4. Path is a directory with `index.html` → serve it; if the request path lacks the trailing slash → 301 to path + `/` (query preserved).
  5. Redirect map hit on decoded path → 301/302 as stored.
  6. Else → `404.html` with status 404.

- [ ] **Step 1: Write failing tests** (`@QuarkusTest`, RestAssured; sample imported + published in `@BeforeEach`)

```java
@Test void servesCyrillicPathUpperAndLowerHex() {
  given().get("/2023/01/14/%D0%BC%D0%B5%D1%81%D1%8F%D1%86-%D0%BD%D0%BE%D1%8F%D0%B1%D1%80%D1%8C-%D0%B3%D0%BE%D0%B4-2022/").then().statusCode(200);
  given().urlEncodingEnabled(false).get("/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/").then().statusCode(200);
}
@Test void missingTrailingSlashRedirects() {
  given().redirects().follow(false).get("/about").then().statusCode(301).header("Location", endsWith("/about/"));
  given().get("/about/index.html").then().statusCode(200);
}
@Test void shortlinkRedirects() {
  given().redirects().follow(false).get("/?p=5994").then().statusCode(301)
    .header("Location", endsWith("/2023/01/14/%d0%bc%d0%b5%d1%81%d1%8f%d1%86-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d1%8c-%d0%b3%d0%be%d0%b4-2022/"));
}
@Test void attachmentPageRedirectsToParent() {
  given().redirects().follow(false).get("/2020/05/10/qa/c/").then().statusCode(301).header("Location", endsWith("/2020/05/10/qa/"));
}
@Test void feedContentType() { given().get("/feed/").then().contentType(startsWith("application/rss+xml")); }
@Test void unknownIs404Page() { given().get("/nope/").then().statusCode(404).body(containsString("<html")); }
@Test void traversalRejected() { given().urlEncodingEnabled(false).get("/%2e%2e/%2e%2e/etc/passwd").then().statusCode(anyOf(is(400), is(404))); }
```

- [ ] **Step 2: Run, verify fail** → FAIL. **Step 3: Implement.** **Step 4: Run, verify pass** → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: serve published site with redirect fallback"`

---

### Task 8: URL parity checker (cutover check #1)

**Files:**
- Create: `verify/UrlParityChecker.java`, `verify/ParityReport.java`, `verify/VerifyUrlsCommand.java`
- Test: `verify/UrlParityCheckerTest.java`

**Interfaces:**
- Produces:
  - `class UrlParityChecker { ParityReport check(URI source, URI target, int maxUrls) }` — collects source URLs from `/wp-sitemap.xml` and `/sitemap_index.xml` (recursively through sub-sitemaps; whichever exist), plus same-host `<a href>` links found on the source home page and on archive pages it reaches (BFS, same host, stop at `maxUrls`); for each path+query, requests it on `target` without following redirects; **pass** = 200, or 301/302/308 whose final (followed) target returns 200 and whose final path equals the source's final path after following the source's own redirects.
  - `record ParityReport(int checked, List<Mismatch> mismatches)`; `record Mismatch(String path, int sourceStatus, int targetStatus, String detail)`; `String toText()`.
  - CLI: `stillpress verify-urls --source <url> --target <url> [--max 20000]`, exit 1 when mismatches non-empty.
  - Request headers: a browser-like `User-Agent`, and a 200 ms delay between source requests (the source sites rate-limit; see spec §8 — the scan got 403s).

- [ ] **Step 1: Write failing tests** — two WireMock servers: "source" with a sitemap listing `/a/`, `/b/`, `/?p=1` (→301 `/a/`); "target" serving `/a/` 200, `/b/` 404, `/?p=1` → 301 `/a/`.

```java
@Test void reportsOnlyRealMismatches() {
  var r = checker.check(source.baseUri(), target.baseUri(), 100);
  assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/b/");
}
@Test void followsSitemapIndex() { /* sitemap_index.xml → post-sitemap.xml → /c/ ; asserts /c/ checked */ }
```

- [ ] **Step 2: Run, verify fail** → FAIL. **Step 3: Implement** (java.net.http client, jsoup for links, StAX for sitemaps). **Step 4: Run, verify pass** → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat: URL parity checker for cutover gate"`

---

### Task 9: End-to-end on the real blog export (manual acceptance)

**Files:** none committed except a short results note `docs/superpowers/plans/2026-11-03-stillpress-01-results.md`.

- [ ] **Step 1: Ask the author for the blog WXR export** — wp-admin → Tools → Export → All content; save as `data-blog/import/blog.xml` (git-ignored). Do not fetch it yourself (production reads need the author).
- [ ] **Step 2: Import** — `STILLPRESS_DATA_DIR=./data-blog mise exec -- java -jar target/quarkus-app/quarkus-run.jar import --wxr data-blog/import/blog.xml` → report printed; record unknown blocks/shortcodes/missing media counts.
- [ ] **Step 3: Publish and serve** — `… publish`, then `… serve` (port 8080).
- [ ] **Step 4: Verify URLs** — `… verify-urls --source https://blog.asm0dey.ru --target http://localhost:8080` → expected: mismatches only in areas owned by later plans (sitemaps, search). Every post, page, archive, pagination, feed and `?p=` URL passes.
- [ ] **Step 5: Write the results note** (counts, mismatch list grouped by cause, which later plan owns each) and commit it.

---

## Self-review notes

- Spec coverage for this plan's slice: storage (ADR 0002) T1; block markup canonical + static HTML passthrough T2–T3; YouTube facade T3; one-shot WXR import, media download, asset paths free, redirect map, password-protected flagged, private/draft unpublished T4; permalinks byte-identical, archives/pagination/feeds/GUIDs T5–T6; atomic republish T6; serving Mode 1 + legacy query URLs + 404 T7; cutover check #1 T8. Everything else (admin, auth, editor, Site Editor, themes, plugins, search, sitemap, comments submission, REST/helper import, nginx Mode 2, podcasting) belongs to plans 02–05.
- Mode 2 (nginx) depends on the `/admin` & comment paths from plan 02/04; its generated include is produced there.
