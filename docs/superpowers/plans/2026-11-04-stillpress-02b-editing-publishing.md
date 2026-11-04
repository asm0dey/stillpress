# Stillpress 02b — Editing and Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users write and edit posts, pages and reusable blocks in the embedded Gutenberg editor (classic items in a Classic block), upload media, save drafts, submit for review, schedule and publish. Publishing rewrites only the files a change affects, in a new atomic release. Every save keeps a revision, and the editor autosaves, previews and restores. The admin speaks English and Russian.

**Architecture:** Same single Quarkus app. Every change that must reach the static site is a row in a `publish_queue` table, written in the transaction that made the change. One db-scheduler task (`publish-site`) drains the queue: it builds a new release by hard-linking every unaffected file from the live one and rendering only the affected item pages and listings (listings are concatenated from cached per-post fragments). It holds a database row lock for the whole run, so the server, the CLI and replicas never publish at the same time. Scheduled items go live through one db-scheduler one-time task per item. The editor is `@wordpress/block-editor` hosted by our own React screen (the spike's approach, not `@wordpress/editor`). It saves through an app-native items API; a WordPress REST slice under `/wp-json` serves only what the block library itself calls.

**Tech Stack:** existing stack (Java 25, Quarkus 3.40.1.1, jOOQ 3.21.9, Flyway, H2/PostgreSQL, Qute, Quinoa 2.9.3, Bun 1.4.4, React 19.3.1, TypeScript 7.0.2, Vite 8.3.5) plus `com.github.kagkarlsson:db-scheduler:15.6.0`; npm (exact pins): `@wordpress/block-editor` 20.0.0, `block-library` 12.1.0, `blocks` 17.1.0, `components` 44.0.0, `core-data` 9.1.0, `data` 11.1.0, `element` 10.1.0, `api-fetch` 8.1.0, `format-library` 6.1.0, `i18n` 7.1.0, `hooks` 5.1.0, `date` 6.1.0, `compose` 9.1.0, `notices` 6.1.0, `tinymce` 4.9.11, `diff` 9.0.0.

**Spec:** `docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md` (§6.2 items 2, 3, 8; §6.2.1; §6.2.4; §6.2.6; §10; §11), plus `CONTEXT.md`, `docs/adr/0001`, `docs/superpowers/plans/ROADMAP.md` (02b scope and the 02a carry-overs).

**This is plan 02b of 02a/02b/02c.** 02c: TOTP, passkeys, CAPTCHA, SMTP reset, invites and notifications.

## Global Constraints

- Everything in plans 01 and 02a's Global Constraints still holds (package root `site.asm0dey.stillpress`, mise, data dir, portable Flyway SQL in `db/migration/common`, jOOQ codegen via `-Pcodegen`, byte-identical permalinks, atomic publish, admin at `/admin`, API under `/admin/api/`, `quarkus-rest-csrf` header `X-CSRF-TOKEN` on every non-GET, session cookie `sp_session`).
- Work on `main`; push every commit to `origin`.
- Binding precedents (copy into every implementer and reviewer prompt): AssertJ only, asserting on values (`containsExactly`, `hasSize`, `isEqualTo`), never `assertThat(boolean).isTrue()` for something that has a value; Hamcrest only inside RestAssured `.body()`. Set-based SQL: one statement for bulk work, no per-row loops. Aggregate in the database where it can. No production interface or wrapper that exists only for tests. Framework capabilities before own code. Admin front end in TypeScript, `tsc --noEmit` in the build. bun, never npm. Trust jOOQ: one jOOQ statement is one statement on H2 and PostgreSQL; no SQL probes. Quarkus docs via `quarkus_searchDocs`; tests via the `quarkus-agent` dev-mode tools, Maven only for spotless, codegen, package and the final `verify`.
- Item statuses are WordPress's strings: `draft`, `pending` (pending review), `future` (scheduled), `publish`, `private`, plus `trash`. Only `publish` items that are not password-protected are ever written to the output (spec §11).
- Role capabilities (WordPress's defaults; `Capabilities` is the single place that decides):

  | | administrator, editor | author | contributor |
  |---|---|---|---|
  | posts: create, edit own | yes | yes | yes, only while `draft`/`pending` |
  | posts: edit others' | yes | no | no |
  | posts: set `publish`/`future`/`private` | yes | own only | no (may set `pending`) |
  | pages: anything | yes | no | no |
  | trash, restore | any | own | own drafts |
  | reusable blocks: create, edit own | yes | yes | no (insert only) |
  | reusable blocks: edit others' | yes | no | no |
  | media upload | yes | yes | no |
  | full republish | administrator only | no | no |

- Optimistic concurrency: every item and reusable-block update carries `expectedModifiedAt`; a mismatch is **409** `{"error":"conflict"}` and changes nothing.
- Permalinks of new items come from setting `permalink_structure` (WordPress tokens `%year% %monthnum% %day% %hour% %minute% %second% %postname% %post_id% %category% %author%`); default `/%year%/%monthnum%/%day%/%postname%/`. The import infers it from the imported posts. Pages are `/<ancestor slugs>/<slug>/`. An item gets its permalink when it is first published. When a published item's permalink changes, the old path gets a 301 to the new one in the redirect map.
- A site's origin is decided once: imported, or never imported. Start empty, or any admin write (items, media, terms, reusable blocks, settings, redirects, users), sets `import_state` to `closed` in the same transaction via `ImportGuard.close()` (Task 1b). An import into a closed or non-empty site is refused (409 in the admin, exit 1 in the CLI). Reset (Task 6d) is the only way back to an import-capable site; it keeps the resetting administrator and the wizard's basics (title, language, site address, timezone).
- Everything an import fills in can also be set in the admin, so a site started empty is a full peer of an imported one: site settings (Task 6b), users, categories and tags, redirects (Task 6c), item author, comment status and page order (Tasks 4, 7).
- New items get a WordPress-style public id: `source_id` = `coalesce(max(source_id), 0) + 1` in the insert. It cannot collide with imported ids, whose `?p=N` redirects already exist. The id feeds `guid` = `<home_url>/?p=<source_id>` (set at creation, never changed, as WordPress does) and, on publish, the redirects `?p=<source_id>` (and `?page_id=<source_id>` for pages) → permalink.
- Site time zone: setting `timezone` (IANA id). The wizard stores the browser's `Intl.DateTimeFormat().resolvedOptions().timeZone`; missing means `UTC`. `published_local` = `published_at` in that zone.
- Revisions: one per save of an item or reusable block (author, time). The newest **25** are kept per object (setting `revisions_keep`). Autosave: one per object and user, written about every **60 s** while there are unsaved edits, replaced on each autosave, deleted on save; not a revision.
- Publish queue rows: `kind` is one of `item` (ref = item id), `base` (ref = listing base path, e.g. `/category/travel/`), `media`, `all`. A save that changes the public site inserts its rows **in the same transaction** as the save.
- Only one publish runs at a time across the server, the CLI and replicas: the publish holds `SELECT … FOR UPDATE` on the `setting` row `publish_lock` (created by V4) on its own non-JTA connection for the whole run.
- db-scheduler runs only when the HTTP server is enabled (`quarkus.http.host-enabled`, as `SetupToken` reads it); the CLI only enqueues or runs publishes directly under the lock.
- Uploads: allowed extensions `jpg jpeg png gif webp avif mp3 m4a ogg wav mp4 webm mov pdf zip`. Never `svg`, `html`, `htm`, `js`, `xml` (they would run as script on the site's origin). Stored under `<data>/media/YYYY/MM/<sanitized name>`, with `-2`, `-3`… on a clash; public URL `/media/YYYY/MM/<name>`.
- The oEmbed proxy fetches only allow-listed providers (`youtube.com`, `youtu.be`, `vimeo.com`), host matched exactly or as a subdomain; anything else is 404 `oembed_invalid_url` without any outbound request.
- WordPress REST slice: prefix `/wp-json/`, authenticated only (`quarkus.http.auth.permission` policy `authenticated`); apiFetch's `POST` + `X-HTTP-Method-Override` is honoured; any unknown route logs WARN `Unknown /wp-json call: <METHOD> <path>?<query>` and answers 404 `{"code":"rest_no_route"}`. `/wp-json` joins `NginxConfig.DYNAMIC_PREFIXES`.
- Admin languages: `en`, `ru`. Per-user `author.locale`; missing means the site `language` if it starts with `ru`, else `en`. Our strings use `@wordpress/i18n` with text domain `stillpress`; Gutenberg's strings use WordPress's community translation (`translate.wordpress.org` project `wp/dev`, locale `ru`, Jed 1.x export), committed as a pinned snapshot.
- The editor bundle is code-split: login, setup and dashboard never download Gutenberg.
- Gutenberg packages and TinyMCE are pinned exactly (spec §9); `bun.lock` committed.

## Review Focus

1. **Incremental publish drifts from a full publish.** After any mix of create, edit, slug change, unpublish, trash and reusable-block edits, the incremental release must equal a fresh full publish file by file. Test `incrementalEqualsFull*` in Task 2.
2. **Two writers at once.** Two tabs or users saving the same item: the second gets 409, not a silent overwrite. Two publishes started together (server task and CLI): exactly one runs at a time and no change is lost. Tests in Tasks 3 and 4.
3. **A save rolls back.** A save that fails after enqueuing (or after scheduling a go-live task) must leave no queue row and no task behind. Test `rolledBackSaveLeavesNoQueueRowOrTask` in Task 3.
4. **Lower roles reaching past their rights through the WordPress slice or the items API.** A contributor publishes via `status: "publish"`, an author edits someone else's post or a page, an author saves someone else's reusable block after editing it inside a post. Each is 403 and changes nothing. Tests in Tasks 4 and 5.
5. **Import sneaking into a site started empty.** A write path that forgets `ImportGuard.close()` reopens the import. `everyWritePathClosesImport` in Task 6c walks every admin write endpoint on a fresh site and then expects the import to be 409.
6. **Hostile uploads and the oEmbed proxy as an open fetcher.** An `.svg`/`.html` upload, a `../` file name, `oembed/1.0/proxy?url=http://169.254.169.254/` → refused, nothing written or fetched. Tests in Tasks 5 and 6.

---

## File Structure

```
pom.xml                                       + db-scheduler
src/main/resources/application.properties     + /wp-json auth policy, media upload policy, quinoa ignored /preview
src/main/resources/db/migration/common/V4__editing.sql
src/main/java/site/asm0dey/stillpress/
  content/Slugs.java                 sanitize_title for post slugs (Unicode kept)
  content/PermalinkStructure.java    render + infer permalink_structure
  content/Capabilities.java          the role table above
  content/ItemService.java           create/update/trash/restore with status rules, revisions, queue rows
  content/Revisions.java             revision + autosave storage and retention
  content/ReusableBlockService.java  save a reusable block, enqueue the items that use it
  content/MediaService.java          upload storage and listing
  publish/PublishQueue.java          enqueue + drain snapshot
  publish/PublishLock.java           row lock on its own connection
  publish/Jobs.java                  db-scheduler Scheduler/SchedulerClient, tasks publish-site and go-live
  publish/PublishStatus.java         progress persisted in setting publish_state
  publish/SitePublisher.java         + incremental mode, fragments, published_item bookkeeping
  publish/views/EntryView.java       one list entry (fragment input)
  store/ItemRepository.java          + editor queries
  store/RevisionRepository.java, store/FragmentRepository.java, store/PublishedItemRepository.java
  store/ReusableBlockRepository.java + CRUD, users of a block
  store/MediaRepository.java         + list/insert
  store/RedirectRepository.java      + put
  store/TermRepository.java          + create
  admin/ItemsResource.java           /admin/api/items, terms, revisions, autosave
  admin/ReusableBlocksResource.java  /admin/api/blocks
  admin/MediaResource.java           /admin/api/media
  admin/PublishResource.java         /admin/api/publish
  admin/PreviewResource.java         /admin/preview/{id}
  admin/SessionLocale (in AuthResource) PUT /admin/api/session/locale
  wpapi/WpApiResource.java           /wp-json slice
  wpapi/MethodOverrideFilter.java    X-HTTP-Method-Override
  wpapi/OEmbedProxy.java             allow-listed oEmbed fetch
src/main/resources/templates/plain/entry.html   list-entry fragment
src/main/webui/src/
  editor/Editor.tsx, editor/Sidebar.tsx, editor/MediaLibrary.tsx, editor/classic.ts, editor/wpapi.ts
  pages/Items.tsx, pages/Blocks.tsx, pages/Revisions.tsx
  i18n/index.ts, i18n/ru.json, i18n/wp-ru.json, i18n/strings.test.ts
```

---

### Task 1: Schema, db-scheduler wiring, slugs, permalink structure

**Files:**
- Create: `V4__editing.sql`, `content/Slugs.java`, `content/PermalinkStructure.java`, `publish/PublishQueue.java`, `publish/PublishLock.java`, `publish/Jobs.java`
- Modify: `pom.xml` (`com.github.kagkarlsson:db-scheduler:15.6.0`), `importer/WordPressImporter.java` (store inferred `permalink_structure` when absent; set `reusable_block.author_id` from the item's creator, as items already do), `admin/SetupResource.java` (accept and store `timezone`), `src/main/webui/src/pages/Setup.tsx` (send the browser zone), `TestStore.java` (`reset()` also clears the new tables and `scheduled_tasks`)
- Test: `content/SlugsTest.java`, `content/PermalinkStructureTest.java`, `publish/PublishLockTest.java`, `publish/JobsTest.java`

**Interfaces:**
- Consumes: `ITEM`, `SETTING`, `DSLContext`, `javax.sql.DataSource` (Agroal default).
- Produces:
  - `V4__editing.sql` (portable H2/PostgreSQL):
    - `ALTER TABLE author ADD COLUMN locale VARCHAR(10)`
    - `ALTER TABLE media ADD COLUMN alt_text TEXT`, `title TEXT`, `width INT`, `height INT`, `size_bytes BIGINT`, `created_at TIMESTAMP`, `uploaded_by BIGINT REFERENCES author (id)`
    - `ALTER TABLE reusable_block ADD COLUMN modified_at TIMESTAMP`, `author_id BIGINT REFERENCES author (id)` — the block's owner; imported blocks get their WXR `dc:creator`, and a block without an owner can be edited only by editors and administrators
    - `CREATE TABLE revision (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, object_type VARCHAR(20) NOT NULL, object_id BIGINT NOT NULL, title TEXT, body TEXT, body_format VARCHAR(10), excerpt TEXT, author_id BIGINT REFERENCES author (id), created_at TIMESTAMP NOT NULL, autosave BOOLEAN NOT NULL DEFAULT FALSE)` + index `(object_type, object_id, created_at)`
    - `CREATE TABLE publish_queue (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, kind VARCHAR(10) NOT NULL, ref VARCHAR(2000), queued_at TIMESTAMP NOT NULL)`
    - `CREATE TABLE item_fragment (item_id BIGINT PRIMARY KEY REFERENCES item (id), html TEXT NOT NULL)`
    - `CREATE TABLE published_item (item_id BIGINT PRIMARY KEY, permalink VARCHAR(2000) NOT NULL, bases TEXT NOT NULL)` — `bases`: the listing bases the item was published into, one per line
    - `INSERT INTO setting (key, value) VALUES ('publish_lock', NULL)`
    - db-scheduler's table, portable form: `CREATE TABLE scheduled_tasks (task_name VARCHAR(100) NOT NULL, task_instance VARCHAR(200) NOT NULL, task_data BYTEA, execution_time TIMESTAMP WITH TIME ZONE NOT NULL, picked BOOLEAN NOT NULL, picked_by VARCHAR(50), last_success TIMESTAMP WITH TIME ZONE, last_failure TIMESTAMP WITH TIME ZONE, consecutive_failures INT, last_heartbeat TIMESTAMP WITH TIME ZONE, version BIGINT NOT NULL, priority SMALLINT, PRIMARY KEY (task_name, task_instance))` + indexes on `execution_time`, `last_heartbeat`, `priority`
    - Re-run `-Pcodegen`.
  - `final class Slugs { static String title(String text) }` — WordPress `sanitize_title` (`remove_accents` + `sanitize_title_with_dashes`): strip tags, remove accents (reuse `Nicename`'s accent table, made package-visible or moved here), lower-case (Unicode), entities and punctuation dropped, whitespace and `.` → `-`, `-` runs collapsed, trimmed; Unicode letters kept (stored decoded, as permalinks are). Empty result → `""`.
  - `final class PermalinkStructure { static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/"; static String render(String structure, Fields f); static Optional<String> infer(List<Fields> posts) /* the candidate matching most posts' actual permalinks: DEFAULT, "/%year%/%monthnum%/%postname%/", "/%postname%/", "/%category%/%postname%/", "/archives/%post_id%/"; empty if none matches at least half */; record Fields(long id, String slug, LocalDateTime local, String categoryPath, String authorSlug, String permalink) {} }`
  - `@ApplicationScoped class PublishQueue { void item(long id); void base(String base); void media(); void all(); record Batch(long upTo, Set<Long> items, Set<String> bases, boolean media, boolean all) {} Optional<Batch> snapshot() /* empty if no rows */; void done(Batch b) /* deletes rows with id <= upTo, one statement */ }` — callers are inside the caller's transaction; `PublishQueue` never starts one.
  - `@ApplicationScoped class PublishLock { <T> T holding(Supplier<T> work) }` — opens a connection from the `DataSource` **outside** any JTA transaction (`QuarkusTransaction.suspendingExisting()` / `@Transactional(NOT_SUPPORTED)` around it), `setAutoCommit(false)`, `SELECT value FROM setting WHERE key = 'publish_lock' FOR UPDATE`, runs `work`, rolls back and closes in `finally`.
  - `@ApplicationScoped class Jobs { SchedulerClient client(); void requestPublish() /* scheduleIfNotExists(publish-site, "site", now) then triggerCheckForDueExecutions() when running */; void scheduleGoLive(long itemId, Instant at) /* instance id "<itemId>@<epochSecond>" */ }` — starts `Scheduler.create(dataSource, tasks).threads(2).pollingInterval(Duration.ofSeconds(10)).serializer(new JacksonSerializer())…` on `StartupEvent` only when the HTTP server is enabled, stops it on `ShutdownEvent`; outside `serve` a `SchedulerClient.Builder.create(dataSource, tasks)` is still available so the CLI can enqueue. Task bodies arrive in Task 3; here they are registered with empty handlers.
  - `POST /admin/api/setup` gains optional field `timezone` (valid `ZoneId` or 400 `field: "timezone"`), stored as setting `timezone`.

- [ ] **Step 1: Write the migration and regenerate jOOQ**

Run: `mise exec -- mvn -q -Pcodegen generate-sources`
Expected: `Tables.REVISION`, `PUBLISH_QUEUE`, `ITEM_FRAGMENT`, `PUBLISHED_ITEM`, `SCHEDULED_TASKS` exist; `AuthorRecord.getLocale()` exists.

- [ ] **Step 2: Write failing tests**

```java
class SlugsTest {
  @Test void cyrillicKeptLowerCased() { assertThat(Slugs.title("Месяц Ноябрь, год 2022")).isEqualTo("месяц-ноябрь-год-2022"); }
  @Test void punctuationAndSpaces() { assertThat(Slugs.title("  Hello, World!  ")).isEqualTo("hello-world"); }
  @Test void accentsRemoved() { assertThat(Slugs.title("Café au lait")).isEqualTo("cafe-au-lait"); }
  @Test void tagsStripped() { assertThat(Slugs.title("<b>Bold</b> move")).isEqualTo("bold-move"); }
  @Test void dotsAndDashRuns() { assertThat(Slugs.title("v1.2 -- final")).isEqualTo("v1-2-final"); }
  @Test void nothingLeft() { assertThat(Slugs.title("!!!")).isEmpty(); }
}

class PermalinkStructureTest {
  Fields f = new Fields(42, "месяц", LocalDateTime.parse("2023-01-14T01:30:00"), "travel/europe", "mama", null);
  @Test void rendersDayAndName() { assertThat(PermalinkStructure.render(PermalinkStructure.DEFAULT, f)).isEqualTo("/2023/01/14/месяц/"); }
  @Test void rendersCategoryAndId() {
    assertThat(PermalinkStructure.render("/%category%/%postname%/", f)).isEqualTo("/travel/europe/месяц/");
    assertThat(PermalinkStructure.render("/archives/%post_id%/", f)).isEqualTo("/archives/42/");
  }
  @Test void infersFromImportedPosts() {
    var posts = List.of(new Fields(1, "a", LocalDateTime.parse("2020-05-02T10:00:00"), "x", "m", "/2020/05/a/"),
                        new Fields(2, "b", LocalDateTime.parse("2021-06-03T10:00:00"), "x", "m", "/2021/06/b/"));
    assertThat(PermalinkStructure.infer(posts)).contains("/%year%/%monthnum%/%postname%/");
  }
  @Test void noMatchInfersNothing() { /* permalinks "/weird/1/", "/odd/2/" → Optional.empty() */ }
}

@QuarkusTest class PublishLockTest {
  @Test void secondHolderWaitsForTheFirst() throws Exception {
    // thread A holds the lock and sleeps 500 ms recording start/end; thread B starts 50 ms later
    // assertThat(bStart).isAfterOrEqualTo(aEnd)
  }
}

@QuarkusTest class JobsTest {
  @Test void schedulerRunsInTests() { assertThat(jobs.client()).isNotNull(); }
  @Test void requestPublishTwiceLeavesOneExecution() {
    // inside one QuarkusTransaction: jobs.requestPublish() twice, then count scheduled_tasks rows with task_name
    // 'publish-site' before commit (the scheduler cannot pick up uncommitted rows) → isEqualTo(1)
  }
}

// WordPressImporterTest addition
@Test void importInfersPermalinkStructure() throws Exception {
  store.importSample();
  assertThat(settings.get("permalink_structure")).contains(PermalinkStructure.DEFAULT);
}
// SetupResourceTest addition: timezone "Europe/Moscow" stored; "Mars/Base" → 400 field "timezone"
```

- [ ] **Step 3: Run tests, verify fail** — `devui-testing_runTest` for each class (via `quarkus_callTool`); Expected: FAIL, classes missing.
- [ ] **Step 4: Implement the types above.**
- [ ] **Step 5: Run tests, verify pass** — same tests → PASS.
- [ ] **Step 6: Commit and push**

```bash
git add pom.xml src
git commit -m "feat: editing schema, db-scheduler, slugs and permalink structure"
git push
```

---

### Task 1b: A site started empty is closed to import

Today `ImportGuard` refuses only when content tables are non-empty, and the wizard's "Start empty" card merely navigates. A site whose administrator chose Start empty, changed settings or added users, but has no posts yet, would still accept an import that overwrites them. The site's origin must be decided once: either it is imported, or it never is.

**Files:**
- Modify: `importer/ImportGuard.java`, `importer/AlreadyImportedException.java` (unchanged API), `admin/SetupResource.java` (new endpoint), `src/main/webui/src/pages/Setup.tsx` (the card calls it), `pages/Import.tsx`, `Shell.tsx`, `pages/Dashboard.tsx` (no import entry once closed), `admin/SiteResource.java` (`GET /site` gains `importAllowed`)
- Test: `importer/ImportGuardTest.java`, `admin/SetupResourceTest.java`, `pg/PostgresImportGuardTest.java`

**Interfaces:**
- Consumes: `SettingRepository.compareAndSet`, `ImportGuard` (02a).
- Produces:
  - `import_state` gains the value `closed`. `ImportGuard.begin()` refuses `closed` with the message "this site was started without an import; import works only on a fresh install".
  - `ImportGuard.close() throws ImportRunningException` (new unchecked exception → 409 `{"error":"import_running"}`): in the caller's transaction, `compareAndSet` from absent or `failed` to `closed`; no-op when already `closed` or `done`; throws when `running`. It works across processes (CLI import), because it is a compare-and-set on the row.
  - `POST /admin/api/setup/start-empty` (`@RolesAllowed("administrator")`) → `close()`, 204. The wizard's Start empty card calls it, then goes to the dashboard.
  - Every admin write path calls `close()` first, in its own transaction: item create and update, media upload, term create, update and delete, reusable-block create and update, settings save, redirect add and delete, user create and edit (Tasks 4, 5, 6, 6b, 6c and the 02a `UsersResource`). The first write decides the site's origin even if the administrator never pressed Start empty. Setup itself does not close import, so "Import from WordPress" stays possible right after the wizard's first step.
  - `GET /admin/api/site` → `importAllowed` = state absent or `failed` and content empty; the Import nav entry, the dashboard's import card and the `/import` route exist only while it is true.
- [ ] **Step 1: Write failing tests**

```java
// ImportGuardTest
@Test void startEmptyClosesImport() throws Exception {
  guard.close();
  assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class).hasMessageContaining("fresh install");
}
@Test void closeDuringImportIsRefused() throws Exception {
  guard.begin();
  assertThatThrownBy(guard::close).isInstanceOf(ImportRunningException.class);
}
@Test void closeAfterFailedImportCloses() throws Exception { guard.begin(); guard.failed("x"); guard.close(); assertThat(guard.state()).contains("closed"); }
// SetupResourceTest
@Test void startEmptyThenImportIs409() { /* setup, POST /setup/start-empty → 204, POST /import → 409 already_imported */ }
@Test void userCreatedBeforeImportClosesIt() { /* setup, POST /users → 201, POST /import → 409 */ }
@Test void importStillAllowedRightAfterSetup() { /* setup only → GET /site importAllowed true, POST /import → 202 */ }
// PostgresImportGuardTest: concurrent begin() and close() → exactly one succeeds
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement**, including the `close()` call in `UsersResource` create and the wizard card.
- [ ] **Step 4: Run, verify pass**, plus `ImportResourceTest`, `ImportCommandTest`.
- [ ] **Step 5: Commit and push** — `git commit -m "feat: a site started empty never accepts an import"`.

---

### Task 2: Incremental publish

**Files:**
- Create: `store/FragmentRepository.java`, `store/PublishedItemRepository.java`, `publish/views/EntryView.java`, `templates/plain/entry.html`
- Modify: `publish/SitePublisher.java`, `publish/views/ArchiveView.java` (entries are ready HTML), `templates/plain/archive.html`
- Test: `publish/IncrementalPublishTest.java`

**Interfaces:**
- Consumes: `PublishQueue.Batch` (Task 1), existing `SitePublisher` walk, `ReleaseSwitcher`, `DataDir.current()`.
- Produces:
  - `SitePublisher.publish(PublishQueue.Batch batch) -> PublishResult`, used by Task 3. `publishAll()` keeps its signature and means a batch with `all = true`.
  - Full mode (`all`, or no live release, or no `published_item` rows): the existing walk. It also rewrites `published_item` for every published item and clears `item_fragment`.
  - Incremental mode:
    1. `old` = `published_item` rows of `batch.items()` (old permalink and bases). `new` = the bases each of those items has now. A post's bases are `/`, each category base with its ancestors, each tag base, its author base, and its year, month and day bases.
    2. Dirty bases = old ∪ new ∪ `batch.bases()`. Dirty paths = for each dirty base `b`: `b`, `b/page/*`, `b/feed/`. For each item in the batch: its old and new permalink plus `feed/` under each. Always dirty: `/comments/feed/`.
    3. New release = hard link of every file in the live release except files under dirty paths (a dirty path covers its own `index.html` only, never deeper item pages: `b/page/N/index.html` and `b/feed/index.html` are excluded, `b/some-post/index.html` is not).
    4. Render the batch's still-published items (page + comment feed), every page and the feed of each dirty base, and `/comments/feed/`. When `batch.media()` is set, link media not yet in the release.
    5. Activate, then (in one transaction) upsert/delete the batch's `published_item` rows.
  - Archive pages are assembled from `item_fragment` (rendered from `plain/entry.html` with an `EntryView(title, permalinkEncoded, publishedAt, excerptHtml)` and stored when missing). `ItemService` (Task 4) deletes an item's fragment whenever it saves that item.
  - `FragmentRepository { Map<Long,String> find(Collection<Long> ids); void put(long id, String html); void delete(Collection<Long> ids); void clear(); }`
  - `PublishedItemRepository { record Row(long itemId, String permalink, List<String> bases) {} Map<Long,Row> find(Collection<Long> ids); void replaceAll(List<Row> rows); void upsert(List<Row> rows); void delete(Collection<Long> ids); }`

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class IncrementalPublishTest {
  // @BeforeEach: store.reset(); store.importSample(); publisher.publishAll();
  // helper tree(Path) -> Map<String relativePath, String sha256> over regular files
  @Test void incrementalEqualsFullAfterTitleEdit() {
    edit(5994, r -> r.setTitle("New title"));  // item with source id 5994: record update + fragments.delete + queue.item(its id)
    var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
    var full = tree(publisher.publishAll().release());
    assertThat(inc).isEqualTo(full);
  }
  @Test void incrementalEqualsFullAfterUnpublish() { /* status → draft: page and its feed gone, archives shrink */ }
  @Test void incrementalEqualsFullAfterPermalinkChange() { /* old path absent, new path present */ }
  @Test void incrementalEqualsFullAfterNewPostShiftsPagination() { /* posts_per_page=2 so a new post moves one entry onto page/2 of home and its category */ }
  @Test void incrementalEqualsFullAfterReusableBlockEdit() { /* edit block content, queue every item that refs it */ }
  @Test void untouchedFilesAreHardLinks() throws Exception {
    // after a title edit: an unrelated post's index.html in the new release has the same fileKey (inode) as in the previous release
    assertThat(Files.readAttributes(after, BasicFileAttributes.class).fileKey())
      .isEqualTo(Files.readAttributes(before, BasicFileAttributes.class).fileKey());
  }
  @Test void noLiveReleaseFallsBackToFull() { /* delete current symlink → publish(batch) writes the complete site */ }
}
```

- [ ] **Step 2: Run, verify fail** — `devui-testing_runTest {"className":"site.asm0dey.stillpress.publish.IncrementalPublishTest"}` → FAIL.
- [ ] **Step 3: Implement** the incremental mode, fragments and `published_item` bookkeeping. Keep the existing `Out.claim` double-write warning working in both modes.
- [ ] **Step 4: Run, verify pass**, then every publish/serve test class (`SitePublisherTest`, `NginxModeTwoTest`, the pg tests) → PASS.
- [ ] **Step 5: Commit and push** — `git commit -m "feat: incremental publish from cached fragments and hard-linked releases"`.

---

### Task 3: Publish jobs: queue drain, go-live, background republish, progress

**Files:**
- Create: `publish/PublishStatus.java`, `admin/PublishResource.java`
- Modify: `publish/Jobs.java` (task bodies), `publish/PublishCommand.java` (full publish under the lock), `importer/ImportJob.java` and `admin/SetupResource.java` (enqueue `all` + `requestPublish()` instead of calling `publishAll()` directly; the import's `done` state is set after the import, and the dashboard shows the publish progress), `src/main/webui/src/pages/Dashboard.tsx` (publish state, "Republish site" for administrators)
- Test: `publish/PublishJobsTest.java`, `admin/PublishResourceTest.java`

**Interfaces:**
- Consumes: `PublishQueue`, `PublishLock`, `SitePublisher.publish(Batch)` (Task 2), `Jobs` (Task 1).
- Produces:
  - Task `publish-site` (one-time, instance `"site"`): `lock.holding(() -> loop { snapshot → publish → done } until snapshot is empty)`. On exception: `PublishStatus.failed(message)`, rows stay queued, and the failure is logged at ERROR. db-scheduler's default retry handles it (failure handler: retry in 1 minute).
  - Recurring task `publish-sweep` (every 1 min): if `publish_queue` has rows and no `publish-site` execution exists, `requestPublish()`. This catches a row enqueued while the previous run was finishing.
  - Task `go-live` (one-time, data = item id): in one transaction, `UPDATE item SET status='publish' WHERE id=? AND status='future' AND published_at <= now(UTC)`. If one row changed, assign the permalink if missing (`ItemService.assignPermalink`, Task 4), enqueue `item`, then `requestPublish()` after commit. Otherwise no-op: a stale task for a rescheduled or unscheduled item does nothing.
  - `@ApplicationScoped class PublishStatus { record State(String state /*idle|queued|running|failed*/, int done, int total, Instant lastPublishedAt, String error) {} State current(); void running(int total); void progress(int done) /* persisted at most once per 200 files */; void finished(); void failed(String message); }` — persisted in setting `publish_state` as JSON so every replica's admin sees it; `queued` = queue rows exist and not running.
  - `GET /admin/api/publish` (any logged-in role) → `State`. `POST /admin/api/publish` (`@RolesAllowed("administrator")`) → enqueue `all`, `requestPublish()`, 202 `State`.
  - CLI `stillpress publish`: `lock.holding(() -> publisher.publish(fullBatchIncludingQueuedRows))`, then `queue.done(batch)`; prints as today.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class PublishJobsTest {
  @Test void queuedItemIsPublishedByTheTask() {
    // enqueue item + requestPublish(); poll (loop + Thread.sleep(100), deadline 20 s) until queue empty
    // → the item's page exists in DataDir.current()
  }
  @Test void scheduledItemGoesLiveAtItsTime() {
    // item status future, published_at = now + 2 s, scheduleGoLive(id, that instant)
    // poll ≤ 20 s → status "publish", page exists in current
  }
  @Test void staleGoLiveIsANoOp() {
    // schedule go-live at now, but the item was moved back to draft → after the task ran, status stays "draft", no page
  }
  @Test void rolledBackSaveLeavesNoQueueRowOrTask() {
    // QuarkusTransaction.requiringNew().run(() -> { queue.item(id); jobs.scheduleGoLive(id, later); throw new RuntimeException(); })
    // → publish_queue empty and scheduled_tasks has no go-live row
    assertThat(db.fetchCount(PUBLISH_QUEUE)).isZero();
  }
  @Test void cliPublishAndTaskNeverOverlap() {
    // run PublishCommand in a thread while a publish-site run holds the lock (a publish with posts_per_page=1 on the sample);
    // record each run's start/end via PublishStatus or log capture → intervals do not overlap; final current is complete
  }
  @Test void failedPublishKeepsRowsAndReportsFailed() { /* make the release dir unwritable → state "failed", rows still queued */ }
}

@QuarkusTest class PublishResourceTest {
  @Test void administratorStartsFullRepublish() { /* POST → 202; poll GET until state idle and lastPublishedAt advanced */ }
  @Test void editorCannotStartIt() { /* 403 */ }
  @Test void anyRoleSeesState() { /* contributor GET → 200 with field state */ }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** the task bodies, `PublishStatus`, the resource, and the Dashboard card (state, progress bar `done/total` while running, last published time, failure message, button for administrators).
- [ ] **Step 4: Run, verify pass**, plus `ImportResourceTest`, `SetupResourceTest`, `ImportCommandTest` (import and setup now publish through the queue; their tests poll for the published file).
- [ ] **Step 5: Commit and push** — `git commit -m "feat: publish queue drained by db-scheduler, scheduled go-live, background republish"`.

---

### Task 4: Items API: statuses, capabilities, revisions, autosave, preview

**Files:**
- Create: `content/Capabilities.java`, `content/ItemService.java`, `content/Revisions.java`, `store/RevisionRepository.java`, `admin/ItemsResource.java`, `admin/PreviewResource.java`
- Modify: `store/ItemRepository.java` (list query with counts), `store/TermRepository.java` (`create(taxonomy, name, parentId)`, `findBySlug(taxonomy, slug)`), `store/RedirectRepository.java` (`put(from, to)`), `application.properties` (`quarkus.quinoa.ignored-path-prefixes=/api,/preview`)
- Test: `content/CapabilitiesTest.java`, `admin/ItemsResourceTest.java`, `content/RevisionsTest.java`, `admin/PreviewResourceTest.java`

**Interfaces:**
- Consumes: `PublishQueue`, `Jobs.scheduleGoLive`, `FragmentRepository.delete`, `Slugs`, `PermalinkStructure`, `Role`, session identity (principal login, role, attribute `userId`).
- Produces:
  - `final class Capabilities { static boolean canEdit(Role r, long userId, ItemRecord item); static boolean canSetStatus(Role r, long userId, ItemRecord item, String status); static boolean canCreate(Role r, String type); static boolean canCreateBlock(Role r); static boolean canEditBlock(Role r, long userId, ReusableBlockRecord block) /* owner (author and up) or editor/administrator */; static boolean canUpload(Role r); static boolean canRepublish(Role r); }` — exactly the Global Constraints table.
  - `@ApplicationScoped class ItemService` (all `@Transactional`):
    - `ItemRecord create(String type /*post|page*/, long authorId)` → `draft`, empty title and body, `body_format` `blocks`, `comment_status` from setting `default_comment_status` (`open` when absent), public id and `guid` per Global Constraints.
    - `ItemRecord update(long id, Update u, Actor actor)`; `record Update(String title, String slug, String body, String bodyFormat, String excerpt, String status, LocalDateTime localDate /* site zone; null = keep */, List<Long> categories, List<Long> tags, Long parentId, Long authorId /* editors and administrators only */, String commentStatus /* open|closed */, Integer menuOrder, LocalDateTime expectedModifiedAt) {}`; `record Actor(long userId, Role role) {}`. Rules, in order:
      1. Conflict check (409).
      2. Capabilities (403).
      3. Slug: blank → `Slugs.title(title)`, else `Slugs.title(slug)`; made unique among items of that type (`-2`, `-3`…).
      4. `publish` with a future date → `future`; `future` without a future date → 400 `{"error":"invalid","field":"date"}`; `publish` with no date → now.
      5. A first publish (or go-live) assigns the permalink via `assignPermalink`. A slug or date change on an already-published item recomputes it, and when the path changes, `redirects.put(old, new)`.
      6. `modified_at` = now; write a revision (`Revisions.saved`); delete this user's autosave.
      7. Delete the item's fragment.
      8. Enqueue `item` when the item is published now or was published before. New terms are enqueued as `base`.
      9. `future` → `jobs.scheduleGoLive(id, published_at)`.
      10. After commit, `jobs.requestPublish()` when something was enqueued.
    - `void assignPermalink(ItemRecord r)` — posts: `PermalinkStructure.render(setting or DEFAULT, fields)`; pages: ancestors' slugs + slug. Unique, because the column is.
    - `void trash(long id, Actor a)` / `void restore(long id, Actor a)` (restore → `draft`); both enqueue `item` if it was published.
  - `@ApplicationScoped class Revisions { void saved(String objectType, long objectId, String title, String body, String bodyFormat, String excerpt, long authorId) /* insert + prune beyond revisions_keep in one DELETE */; void autosave(String objectType, long objectId, long userId, String title, String body, String excerpt) /* replace */; Optional<RevisionRecord> autosaveOf(String objectType, long objectId, long userId); List<RevisionRecord> list(String objectType, long objectId) /* newest first, no autosaves */; Optional<RevisionRecord> find(long id); }`
  - REST (`/admin/api`, logged-in; the service enforces capabilities):
    - `GET /items?type=post|page&status=<s>|any&mine=true&search=&page=1` → `{"items":[{"id","type","title","status","author","date","modified","permalink"}],"total","counts":{"draft":n,"pending":n,…}}`, 20 per page, newest modified first. Contributors and authors see only their own; editors and administrators see all. `counts` comes from one `GROUP BY` query.
    - `POST /items` `{"type"}` → 201 item. `GET /items/{id}` → item `{"id","type","title","slug","status","body","bodyFormat","excerpt","date" /*site-local ISO or null*/,"permalink","categories","tags","authorId","parentId","modifiedAt","autosave": {"createdAt"} | null /* only if newer than modifiedAt */}`.
    - `PUT /items/{id}` `Update` JSON → 200 item; 409 conflict; 403; 400 invalid.
    - `DELETE /items/{id}` → 204 (trash). `POST /items/{id}/restore` → 200 item.
    - `PUT /items/{id}/autosave` `{"title","body","excerpt"}` → 204. `GET /items/{id}/autosave` → `{"title","body","excerpt","createdAt"}` or 404.
    - `GET /items/{id}/revisions` → `[{"id","author","createdAt"}]`. `GET /revisions/{rid}` → `{"id","title","body","excerpt","createdAt","author"}` (same capability as the object). `POST /items/{id}/revisions/{rid}/restore` → an ordinary `update` carrying the revision's title, body and excerpt, with the item's current status, so restoring a published item is a normal Update (spec §6.2.6).
    - `GET /terms?taxonomy=category|post_tag` → `[{"id","name","slug","parentId"}]`. `POST /terms` `{"taxonomy","name","parentId"}` (authors and up) → 201; slug via `Slugs.title`, unique per taxonomy.
  - `GET /admin/preview/{id}` (logged-in, `canEdit`) → `text/html` from `plain/single` using this user's autosave if newer than the item, else the item. Headers `Cache-Control: no-store`, `X-Robots-Tag: noindex`. Nothing is written to the output directory.

- [ ] **Step 1: Write failing tests**

```java
class CapabilitiesTest {
  // ItemRecord fixtures: post by user 1 (draft, publish), page by user 1
  @Test void contributorCannotPublishButMaySubmitForReview() {
    assertThat(Capabilities.canSetStatus(CONTRIBUTOR, 1, draftPostBy1, "publish")).isFalse();
    assertThat(Capabilities.canSetStatus(CONTRIBUTOR, 1, draftPostBy1, "pending")).isTrue();
  }
  @Test void contributorCannotEditOwnPublishedPost() { assertThat(Capabilities.canEdit(CONTRIBUTOR, 1, publishedPostBy1)).isFalse(); }
  @Test void authorCannotTouchOthersOrPages() {
    assertThat(Capabilities.canEdit(AUTHOR, 2, draftPostBy1)).isFalse();
    assertThat(Capabilities.canCreate(AUTHOR, "page")).isFalse();
  }
  @Test void editorDoesEverythingButRepublish() {
    assertThat(Capabilities.canEdit(EDITOR, 2, pageBy1)).isTrue();
    assertThat(Capabilities.canRepublish(EDITOR)).isFalse();
  }
}

@QuarkusTest class ItemsResourceTest {
  // @BeforeEach: reset; setting timezone Europe/Moscow; users admin, editor, author1, author2, contributor with cookies
  @Test void createEditPublishWritesPageAtStructurePermalink() {
    // author1: POST /items {type: post} → PUT {title: "Привет мир", status: "publish", expectedModifiedAt}
    // → permalink "/<yyyy>/<mm>/<dd>/привет-мир/" (Moscow date); poll until current/<that>/index.html exists
  }
  @Test void staleExpectedModifiedAtIs409AndChangesNothing() { }
  @Test void contributorPublishIs403AndStatusUnchanged() { }
  @Test void authorEditingOthersPostIs403() { }
  @Test void futureDateMakesItScheduled() { /* status publish + date tomorrow → "future", no page, a go-live row in scheduled_tasks */ }
  @Test void futureStatusWithPastDateIs400() { }
  @Test void slugChangeOnPublishedPostRedirectsOldPath() {
    // publish, then PUT slug "new-slug" → redirects.find(oldPath).orElseThrow().getToPath() == newPath
  }
  @Test void duplicateTitlesGetUniqueSlugs() { /* two posts "Same" published same day → "same", "same-2" */ }
  @Test void trashUnpublishesAndRestoreGivesDraft() { }
  @Test void newItemIdSkipsImportedIdsAndGetsShortlink() {
    // after importSample (max source id S): a new published post has guid "<home>/?p=<S+1>" and redirect "?p=<S+1>" → its permalink;
    // the imported "?p=5994" redirect is unchanged
  }
  @Test void editorReassignsAuthorButAuthorCannot() { /* editor PUT authorId → saved; author1 PUT authorId author2 → 403 */ }
  @Test void listShowsOnlyOwnForAuthorsAndCountsByStatus() { }
  @Test void restoringRevisionOfPublishedItemRepublishes() { }
}

@QuarkusTest class RevisionsTest {
  @Test void keepsNewest25() {
    // 30 saves → list(...) hasSize(25) and the oldest kept is save #6
  }
  @Test void autosaveIsReplacedNotAccumulatedAndDeletedOnSave() { }
  @Test void autosaveIsNotARevision() { }
}

@QuarkusTest class PreviewResourceTest {
  @Test void previewShowsAutosaveAndWritesNothing() {
    // published item; autosave with title "Draft title" → GET /admin/preview/{id} contains "Draft title",
    // header Cache-Control no-store; current/<permalink>/index.html still has the old title
  }
  @Test void anonymousIs401AndOtherAuthorIs403() { }
  @Test void previewIsNotSwallowedByQuinoa() { /* content type text/html and body has no <div id="root" */ }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass** (these classes plus `IncrementalPublishTest`, `PublishJobsTest`).
- [ ] **Step 5: Commit and push** — `git commit -m "feat: items API with statuses, capabilities, revisions, autosave and preview"`.

---

### Task 5: Reusable blocks and the WordPress REST slice

**Files:**
- Create: `content/ReusableBlockService.java`, `admin/ReusableBlocksResource.java`, `wpapi/WpApiResource.java`, `wpapi/MethodOverrideFilter.java`, `wpapi/OEmbedProxy.java`
- Modify: `store/ReusableBlockRepository.java`, `serve/NginxConfig.java` (`/wp-json` in `DYNAMIC_PREFIXES`), `application.properties` (`quarkus.http.auth.permission.wp-json.paths=/wp-json/*`, `policy=authenticated`)
- Test: `content/ReusableBlockServiceTest.java`, `wpapi/WpApiResourceTest.java`, `wpapi/OEmbedProxyTest.java`, `serve/NginxConfigTest.java` (asserts `location ^~ /wp-json`)

**Interfaces:**
- Consumes: `Revisions`, `PublishQueue`, `Jobs`, `Capabilities`, `BlockParser`, `MediaRepository` (Task 6 adds listing; here only `findById`).
- Produces:
  - Reusable-block ids: the id everywhere (REST, `wp:block {"ref":N}`) is `source_id`. A block created here gets `source_id = coalesce(max(source_id), 0) + 1` in its insert statement (the unique constraint guards the race; retry once on violation).
  - `ReusableBlockRepository { Optional<String> content(long ref) /* unchanged */; List<ReusableBlockRecord> all(); Optional<ReusableBlockRecord> find(long ref); ReusableBlockRecord create(String title, String content); void update(long ref, String title, String content); List<Long> itemsUsing(Set<Long> refs) /* body LIKE '%"ref":N}%' or '%"ref":N,%' per ref in one query, then confirmed with BlockParser */ }`
  - `ReusableBlockService.save(Long ref /*null = create*/, String title, String content, LocalDateTime expectedModifiedAt, ItemService.Actor a) -> ReusableBlockRecord` (`@Transactional`): create needs `canCreateBlock` and sets `author_id` = the actor; update needs `canEditBlock` (403), conflict check (409), revision `reusable_block`, then enqueue `item` for every published item using this block or a block that (transitively, depth ≤ `BlockRenderer.MAX_DEPTH`) contains it; their fragments are not touched (archives show excerpts only).
  - App API: `GET /admin/api/blocks` `[{"id","title","author","modifiedAt","usedBy","canEdit"}]`, `GET /admin/api/blocks/{id}`, `POST`, `PUT /{id}` (with `expectedModifiedAt`), revisions as for items (`/blocks/{id}/revisions`, restore).
  - `/wp-json` slice (`WpApiResource`, JSON in WordPress's shapes):
    - `GET /wp/v2/blocks`, `GET /wp/v2/blocks/{id}`, `POST /wp/v2/blocks`, `POST|PUT /wp/v2/blocks/{id}` → `{"id","type":"wp_block","status":"publish","wp_pattern_sync_status":"","title":{"raw","rendered"},"content":{"raw","protected":false},"wp_pattern_category":[]}`; writes go through `ReusableBlockService` (in-post edits of a synced pattern are saved as dirty entities; an author editing a block they do not own gets 403).
    - `OPTIONS` on `/wp/v2/blocks` and `/wp/v2/blocks/{id}` → 200 with `Allow: GET` or `Allow: GET, POST, PUT, PATCH, DELETE` by role (core-data's `canUser`).
    - `GET /wp/v2/types` and `/wp/v2/types/{slug}` (`post`, `page`, `wp_block`, `attachment`), `GET /wp/v2/themes?status=active` (one theme `plain`, `theme_supports`: `align-wide`, `responsive-embeds`, `editor-styles`).
    - `GET /wp/v2/media/{id}` and `GET /wp/v2/media?include=…&per_page=…` → `{"id","source_url","media_type":"image|file","mime_type","alt_text","title":{"rendered"},"media_details":{"width","height","sizes":{}}}`; unknown id 404 `rest_post_invalid_id` (imported content refers to WordPress attachment ids; 404 is harmless, as the spike showed).
    - `GET /wp/v2/users/me` → `{"id","name","slug"}`.
    - `GET /oembed/1.0/proxy?url=` → `OEmbedProxy.fetch(url)`: provider JSON, or 404 `{"code":"oembed_invalid_url"}`.
    - Catch-all for any method on any other path: the WARN log line and 404 from Global Constraints.
  - `MethodOverrideFilter`: JAX-RS `@PreMatching ContainerRequestFilter`, only for `/wp-json/` requests that are `POST` with `X-HTTP-Method-Override: PUT|PATCH|DELETE` → `setMethod(header)`. CSRF still applies, because the original request was a POST.
  - `final class OEmbedProxy { Response fetch(String url) }` — allow list from Global Constraints, `URI` parsed and host compared exactly or by `.`-suffix, `https` only, provider endpoints `https://www.youtube.com/oembed?format=json&url=` and `https://vimeo.com/api/oembed.json?url=`; JDK `HttpClient` with 5 s timeout, no redirects followed; non-200 → 404 `oembed_invalid_url`.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class ReusableBlockServiceTest {
  @Test void newBlockIdContinuesAfterImportedOnes() { /* sample's max source_id S → created block id S+1 */ }
  @Test void editEnqueuesEveryItemUsingItIncludingNested() {
    // block A contains <!-- wp:block {"ref":B} /-->; item X refs A, item Y refs B, item Z refs neither
    // save B → queue items contain X and Y, not Z
  }
  @Test void refPrefixDoesNotMatch() { /* item refs 123; editing block 12 does not enqueue it */ }
  @Test void authorEditsOwnBlockButNotOthers() {
    // author1 creates a block → author_id = author1; author1 updates it → saved; author2 updates it → 403, content unchanged
  }
  @Test void contributorCannotCreate() { /* 403 */ }
  @Test void ownerlessImportedBlockOnlyForEditors() { /* block with author_id null: author → 403, editor → saved */ }
}

@QuarkusTest class WpApiResourceTest {
  @Test void anonymousIs401() { given().get("/wp-json/wp/v2/types").then().statusCode(401); }
  @Test void methodOverridePutUpdatesBlock() {
    // editor: POST /wp-json/wp/v2/blocks/{id} with X-HTTP-Method-Override: PUT, body {content: "<!-- wp:paragraph -->…"} → 200, content.raw updated
  }
  @Test void overrideWithoutCsrfIs400() { }
  @Test void optionsAllowDependsOnRole() {
    // per block: editor → Allow contains "PUT"; the owning author → contains "PUT"; another author → equalTo "GET"
    // collection /wp/v2/blocks: author → contains "POST"; contributor → equalTo "GET"
  }
  @Test void unknownRouteIs404AndLogged() { /* GET /wp-json/wp/v2/settings → 404, body code rest_no_route */ }
  @Test void typesListsPostPageAndBlock() { }
}

class OEmbedProxyTest {
  // WireMock is on the test classpath; the proxy's endpoint map is constructor-injectable for the test
  @Test void metadataAddressIsRefusedWithoutFetching() { /* url http://169.254.169.254/ → 404, WireMock saw 0 requests */ }
  @Test void lookAlikeHostIsRefused() { /* https://youtube.com.evil.example/watch?v=x → 404 */ }
  @Test void youtubeIsProxied() { /* stub the provider endpoint → 200 body passed through */ }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass** (plus `NginxConfigTest`, `NginxModeTwoTest`).
- [ ] **Step 5: Commit and push** — `git commit -m "feat: reusable blocks service and the WordPress REST slice for the editor"`.

---

### Task 6: Media library backend and upload limits

**Files:**
- Create: `content/MediaService.java`, `admin/MediaResource.java`
- Modify: `store/MediaRepository.java`, `account/BodyLimitFilter.java` (exclude `POST /admin/api/media`), `application.properties` (`quarkus.http.auth.permission.media-upload.paths=/admin/api/media`, `policy=authenticated`), `templates/nginx/stillpress.conf` (`client_max_body_size 1g` only in `location = /admin/api/import` and `location = /admin/api/media`; `^~ /admin` and `^~ /wp-json` get `client_max_body_size 1m`)
- Test: `content/MediaServiceTest.java`, `admin/MediaResourceTest.java`, `serve/NginxConfigTest.java`, `admin/AdminBodyLimitTest.java`

**Interfaces:**
- Consumes: `DataDir.media()`, `Capabilities.canUpload`, `PublishQueue.media()`, `Jobs.requestPublish()`.
- Produces:
  - `MediaService.store(String fileName, Path upload, long userId) -> MediaRecord` (`@Transactional`): extension allow list (Global Constraints, case-insensitive) else `IllegalArgumentException("type_not_allowed")`; the name is reduced to its last path segment and run through `Slugs.title` on the base name, keeping the lower-cased extension (`../../x.PNG` → `x.png`); unique in `YYYY/MM` (current month in the site zone); file moved in; `width`/`height` via `ImageIO` when it can read the image (null otherwise); `mime_type` from the extension; `source_url` = the public path; enqueue `media`, `requestPublish()` after commit.
  - `MediaRepository { Optional<MediaRecord> findById(long id); record Page(List<MediaRecord> items, int total) {} Page list(String type /*image|audio|video|file|null*/, String search, int page, int perPage); void updateMeta(long id, String title, String altText); }`
  - `POST /admin/api/media` multipart `file` → 201 `{"id","url","mimeType","width","height","title","alt"}`; 400 `{"error":"type_not_allowed"}`; 403 contributor. `GET /admin/api/media?type=&search=&page=` → `{"items":[…],"total"}`; `PUT /admin/api/media/{id}` `{"title","alt"}`.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class MediaResourceTest {
  @Test void uploadIsStoredListedAndPublished() {
    // author uploads a 3×2 PNG "My Photo.PNG" → 201 url "/media/<yyyy>/<mm>/my-photo.png", width 3, height 2;
    // poll until current/media/<yyyy>/<mm>/my-photo.png exists
  }
  @Test void svgAndHtmlRefused() { /* "x.svg", "x.html" → 400 type_not_allowed, nothing under media/ */ }
  @Test void traversalNameIsFlattened() { /* "../../evil.png" → stored as media/<yyyy>/<mm>/evil.png */ }
  @Test void clashGetsSuffix() { /* two "a.png" → a.png, a-2.png */ }
  @Test void contributorIs403() { }
  @Test void uploadAboveJsonLimitIsAccepted() { /* 2 MB file → 201 (BodyLimitFilter exclusion) */ }
}
// NginxConfigTest: the include has "location = /admin/api/media" with 1g and "location ^~ /admin" with 1m
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass** (plus `NginxModeTwoTest`: a 2 MB POST to `/admin/api/users` through nginx gets 413 from nginx without reaching Stillpress).
- [ ] **Step 5: Commit and push** — `git commit -m "feat: media upload and library API; per-location upload limits"`.

---

### Task 6b: Site settings and a blog from scratch

A site started empty in the wizard must work as a blog without any WordPress import: settings can be edited, the first post lands in a default category, and archive pages show excerpts.

**Files:**
- Create: `admin/SettingsResource.java`, `src/main/webui/src/pages/Settings.tsx`
- Modify: `content/ItemService.java` (default category), `publish/views/ViewFactory.java` or `EntryView` (automatic excerpt), `main.tsx` + `Shell.tsx` (route `/settings`, administrators only)
- Test: `admin/SettingsResourceTest.java`, `admin/FromScratchBlogTest.java`

**Interfaces:**
- Consumes: `SettingRepository`, `TermRepository.create`, `PublishQueue.all()`, `Jobs.requestPublish()`, `PermalinkStructure`.
- Produces:
  - `GET /admin/api/settings` / `PUT /admin/api/settings` (`@RolesAllowed("administrator")`): `{"title","description","language","homeUrl","timezone","postsPerPage","postsPerRss","permalinkStructure","categoryBase","tagBase","defaultCategory","defaultCommentStatus"}`. Validation: language as in setup; timezone a `ZoneId`; both counts 1–100; `permalinkStructure` starts and ends with `/` and contains `%postname%` or `%post_id%`; `defaultCategory` an existing category id or null. A bad field → 400 `{"error":"invalid","field"}`. `homeUrl` is an absolute `http(s)` URL without a path; `categoryBase`/`tagBase` are one non-empty slug each and differ from each other. Saving title, description, language, `homeUrl`, either count or either base enqueues `all`; a base change also adds a 301 from each old archive base to the new one (spec §6.2.1: a settings change triggers the background full republish). A permalink-structure change applies only to items published afterwards: existing permalinks never change (spec §9), and the screen says so.
  - Default category: when a post is saved with no categories and is published (now or by go-live), it gets setting `default_category`. If that setting is unset, `ItemService` uses the category with slug `Slugs.title(name)`, creating it if missing, where name = "Без рубрики" when the site language starts with `ru`, else "Uncategorized". It then stores its id as `default_category`. The term is created lazily, never at setup, because the import guard requires an empty `term` table.
  - Automatic excerpt (WordPress `wp_trim_excerpt`): an entry whose `excerpt` is blank shows the first **55** words of the rendered body with tags stripped, followed by ` […]` when it was cut. Feeds keep using the stored excerpt as today.
  - `Settings.tsx`: one form with the fields above. Permalink presets: Day and name (`DEFAULT`), Month and name, Post name, Numeric (`/archives/%post_id%/`), Custom. The default-category select lists the categories. Linked from the dashboard for administrators.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class SettingsResourceTest {
  @Test void titleChangeRepublishesTheWholeSite() {
    // PUT title "New name" → publish_queue has an "all" row; poll until current/index.html contains "New name"
  }
  @Test void permalinkStructureChangeKeepsExistingPermalinks() {
    // published post at /2026/11/04/a/; PUT permalinkStructure "/%postname%/"; publish post b
    // → a still at its old path, b at "/b/"
  }
  @Test void invalidFieldsAre400() { /* postsPerPage 0, timezone "Mars/Base", structure "/%year%/" → field names */ }
  @Test void editorIs403() { }
}

@QuarkusTest class FromScratchBlogTest {
  @Test void startEmptyThenFirstPostMakesAWorkingBlog() {
    // reset; POST /admin/api/setup (language ru-RU, timezone Europe/Moscow); no import
    // create a post "Первый пост" with a 100-word paragraph and no categories, publish; poll until published
    // home "/" lists it; /category/без-рубрики/ lists it; /feed/ has its <guid>; /author/<login>/ lists it;
    // the home entry text ends with " […]" and has 55 words
    assertThat(terms.findBySlug("category", "без-рубрики")).isPresent();
  }
  @Test void importAfterSetupStillAllowed() {
    // setup (no post written) → the import guard still accepts an import (no term was created)
  }
  @Test void firstPostClosesImport() { /* setup, publish a post → POST /admin/api/import → 409, GET /site importAllowed false */ }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass**, plus `IncrementalPublishTest` (the automatic excerpt changes archive output in both modes alike).
- [ ] **Step 5: Commit and push** — `git commit -m "feat: site settings screen; a blog from scratch gets a default category and excerpts"`.

---

### Task 6c: Back office for everything the import fills in

Whatever an import sets must also be settable by hand, so a site started empty is a full peer of an imported one. Import writes: site settings (Task 6b), users, categories and tags, item fields (author, comment status, page order; Tasks 4 and 7), and the redirect map. This task adds the screens that are still missing.

**Files:**
- Create: `admin/TermsResource.java`, `admin/RedirectsResource.java`, `src/main/webui/src/pages/Terms.tsx`, `pages/Redirects.tsx`
- Modify: `admin/UsersResource.java` + `pages/Users.tsx` (edit display name and email), `store/TermRepository.java` (`update`, `delete`), `store/RedirectRepository.java` (`list`, `delete`), `main.tsx` + `Shell.tsx` (routes `/categories`, `/tags`, `/redirects`)
- Test: `admin/TermsResourceTest.java`, `admin/RedirectsResourceTest.java`, `admin/UsersResourceTest.java`, `admin/ImportClosedTest.java`

**Interfaces:**
- Consumes: `PublishQueue`, `Jobs.requestPublish()`, `Slugs`, `Capabilities`.
- Produces:
  - Terms (editors and administrators; authors keep creating terms from the editor via `POST /admin/api/terms`):
    - `GET /admin/api/terms?taxonomy=` gains `count` and `description` (one aggregated query).
    - `PUT /admin/api/terms/{id}` `{"name","slug","parentId","description"}`: slug via `Slugs.title`, unique per taxonomy (409 `slug_taken`), a parent cycle is 400 `field: "parentId"`. A slug or parent change moves the archive: enqueue the old and new bases plus the bases of every descendant, add a 301 from the old base to the new one, and delete the fragments of the term's items.
    - `DELETE /admin/api/terms/{id}`: refused for the default category (409 `default_category`). Children move to the deleted term's parent. Its posts lose the term, and a post left with no category gets the default one. Enqueue the term's base and every affected item.
  - Redirects (administrators): `GET /admin/api/redirects?search=&page=` → `{"items":[{"from","to","status"}],"total"}`. `POST` `{"from","to","status": 301|302}`: `from` is a decoded path starting with `/` or a query starting with `?`, `to` is a path or an absolute `http(s)` URL; a `from` equal to a published item's permalink is 409 `shadows_item`. `DELETE /admin/api/redirects?from=`. No publish is needed: redirects are served by Stillpress in both modes.
  - Users: `PUT /admin/api/users/{id}` `{"displayName","email"}` (administrators; any user may edit their own) with the Task 12 validation; a display-name change enqueues the user's author base. Author slugs, and so `/author/<slug>/` URLs, never change.
  - Screens: Categories (tree with parent select) and Tags share `Terms.tsx`; Redirects is a searchable table with add and delete. Both are in the nav for the roles above.

- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class TermsResourceTest {
  @Test void renameSlugMovesArchiveAndRedirectsOldOne() {
    // category "travel" with child "europe" and a published post in europe; PUT slug "trips"
    // → poll: current/category/trips/europe/index.html exists, category/travel/ is gone,
    //   redirects.find("/category/travel/").orElseThrow().getToPath() == "/category/trips/"
  }
  @Test void deleteMovesPostsToDefaultCategoryAndChildrenUp() { }
  @Test void defaultCategoryCannotBeDeleted() { }
  @Test void parentCycleIs400() { }
  @Test void authorIs403ForEditButMayCreate() { }
}

@QuarkusTest class RedirectsResourceTest {
  @Test void addedRedirectIsServed() {
    // POST {from "/old-page/", to "/2023/01/14/месяц-ноябрь-год-2022/", status 301}
    // → GET /old-page/ (no follow) → 301 with that Location (percent-encoded)
  }
  @Test void cannotShadowAPublishedItem() { /* 409 shadows_item */ }
  @Test void deleteRemovesIt() { }
  @Test void editorIs403() { }
}
@QuarkusTest class ImportClosedTest {
  // one test per write path, each on a freshly reset site after setup: items POST, media POST, terms POST, blocks POST,
  // settings PUT, redirects POST, users POST and PUT → then POST /admin/api/import → 409 already_imported
  @ParameterizedTest @MethodSource("writes") void everyWritePathClosesImport(Consumer<RequestSpecification> write) { }
}
// UsersResourceTest: user edits own display name → 204 and author archive heading changes after publish;
// author edits someone else → 403
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass**, plus `IncrementalPublishTest` (a term move must still equal a full publish: add `incrementalEqualsFullAfterTermSlugChange` there).
- [ ] **Step 5: Commit and push** — `git commit -m "feat: categories, tags, redirects and user profile screens"`.

---

### Task 6d: Reset the site

Lets an administrator try Stillpress on a fresh install, then wipe the experiment and import (or start empty again) without touching the data directory by hand. It also replaces "wipe the data directory and run setup again" as the way to redo a bad import.

**Files:**
- Create: `content/SiteReset.java`, `admin/ResetResource.java`, `ResetCommand.java` (CLI, registered in `StillpressCommand`)
- Modify: `pages/Settings.tsx` (a "Reset site" section at the bottom), `importer/ImportJob.java` and `pages/Import.tsx` (their "wipe the data directory" texts point to Reset instead), `README.md` (one paragraph)
- Test: `content/SiteResetTest.java`, `admin/ResetResourceTest.java`, `ResetCommandTest.java`

**Interfaces:**
- Consumes: `PublishLock`, `ImportGuard`, `SitePublisher.publishAll()`, `DataDir`, `LoginLimiter`, `Passwords`, the generated jOOQ schema (`site.asm0dey.stillpress.db.DefaultSchema` or `Public`, whichever codegen produced).
- Produces:
  - `@ApplicationScoped class SiteReset { void reset(long keepAdministratorId); void wipe(); }`
    - `reset` (admin): under `PublishLock`, one transaction, refused with `ImportRunningException` while an import runs. It deletes every row of every content table in foreign-key order: items and everything hanging off them, terms, comments, media, reusable blocks, revisions, redirects, the publish queue, fragments, `published_item`, user tokens, every session except the caller's, every user except `keepAdministratorId`, and `scheduled_tasks` rows except recurring tasks.
    - Settings: deletes all except `title`, `language`, `home_url`, `timezone` and `publish_lock`, then writes setup's defaults again (`posts_per_page` 10, `posts_per_rss` 10, `category_base`, `tag_base`). `import_state` is removed, so import is open again.
    - Files: deletes the contents of `media/` and `import/`, publishes the now-empty site (the old content stops being served at once), then deletes every other release.
    - The administrator's `source_id` is cleared, so the next import merges by login as on a fresh install.
  - `wipe` (CLI): the same, but it also deletes every user and every setting except `publish_lock`, so the next `serve` prints a new setup token and the wizard starts from step 1.
  - `POST /admin/api/reset` (`@RolesAllowed("administrator")`) `{"password","confirm"}`: `confirm` must equal the site title exactly (400 `field: "confirm"`). The password is checked like a login and counts towards the same `LoginLimiter` (wrong → 403 `{"error":"invalid_credentials"}`, over the limit → 429). 409 `import_running`. Success → 204; the caller stays logged in and lands on the dashboard with the Start empty / Import choice.
  - CLI `stillpress reset --yes`: `wipe()`. Without `--yes` it prints what would be deleted (counts per table and the data directory path) and exits 1.
  - Settings screen: a "Reset site" section, visually separated, stating "Deletes all posts, pages, media, users other than you, categories, redirects and settings. This cannot be undone; copy the data directory first if you may want it back." The password and title fields are filled in before the button enables.
- [ ] **Step 1: Write failing tests**

```java
@QuarkusTest class SiteResetTest {
  @Test void resetLeavesOnlyTheAdministratorAndWizardSettings() {
    // setup, start empty, then a published post with an uploaded image, a term, a block, a redirect, a second user,
    // a settings change; reset(adminId)
    // every table in the generated schema except author, setting, flyway_schema_history, scheduled_tasks has 0 rows;
    // author has exactly the admin (password kept); setting keys are exactly the kept and default ones;
    // media/ is empty; current/index.html exists and lists no posts; only one release directory remains
    assertThat(tablesWithRows()).containsExactlyInAnyOrder("author", "setting", "flyway_schema_history");
  }
  @Test void importWorksAfterReset() { /* reset, then store.importSample() via ImportGuard.begin → report items 6 */ }
  @Test void refusedWhileImportRuns() { /* guard.begin(); assertThatThrownBy(() -> reset.reset(adminId)).isInstanceOf(ImportRunningException.class) */ }
  @Test void wipeLeavesNoUsersAndASetupToken() { /* wipe() → author empty; SetupToken after onStart → present */ }
}

@QuarkusTest class ResetResourceTest {
  @Test void wrongPasswordIs403AndNothingDeleted() { }
  @Test void wrongConfirmIs400() { }
  @Test void otherUsersAreLoggedOutCallerIsNot() { /* editor's cookie → 401 after reset; admin's cookie → 200 on /session */ }
  @Test void editorIs403() { }
}
// ResetCommandTest: without --yes exits 1 and deletes nothing; with --yes empties the store
```

`tablesWithRows()` iterates the generated schema's tables, so a table added by a later plan fails this test until reset covers it.

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass**, plus `ImportGuardTest`, `SetupResourceTest`.
- [ ] **Step 5: Commit and push** — `git commit -m "feat: reset the site from the admin or the CLI"`.

---

### Task 7: The editor screen

**Files:**
- Create: `src/main/webui/src/editor/Editor.tsx`, `editor/Sidebar.tsx`, `editor/MediaLibrary.tsx`, `editor/wpapi.ts`, `pages/Items.tsx`
- Modify: `package.json` (the npm pins from Tech Stack), `main.tsx` (routes `/posts`, `/pages`, `/edit/:id`, editor lazy-loaded), `Shell.tsx` (nav: Posts, Pages, Reusable blocks, Media; by capability), `api.ts` (types `Item`, `ItemSummary`, `Term`, `Media`, `PublishState`)
- Test: browser check (Step 5); `tsc --noEmit` in the build

**Interfaces:**
- Consumes: Tasks 4–6 APIs.
- Produces:
  - `editor/wpapi.ts`: `apiFetch.use(apiFetch.createRootURLMiddleware('/wp-json/'))` plus a middleware adding `X-CSRF-TOKEN` from the cookie (the same read as `api.ts`, extracted into an exported `csrfToken()`), called once before the editor mounts; `registerCoreBlocks()` once.
  - `Items.tsx`: list for `type` post or page. Status filter tabs from `counts` (All, Drafts, Pending, Scheduled, Published, Private, Trash), search, pages of 20, "Add new". Rows link to `/edit/:id`; Trash and Restore actions.
  - `Editor.tsx` (route `/edit/:id`):
    - Loads the item. If `autosave` is present, a notice "There is an autosave of this post that is more recent than the version below. View the autosave / Dismiss"; "View" loads `GET /items/{id}/autosave` into the editor.
    - Title input. Blocks parsed with `parse(body)` for `blocks` format; the classic format arrives in Task 8.
    - `BlockEditorProvider` + `BlockCanvas` (iframe) with block-library styles. Settings: `mediaUpload` → `POST /admin/api/media`; `__experimentalFetchLinkSuggestions` → `GET /admin/api/items?search=&status=publish`; `__experimentalFeatures: { layout: { contentSize: '650px', wideSize: '1200px' }, spacing: { blockGap: true } }`; `hasFixedToolbar: false`.
    - Save bar by capability: "Save draft", "Submit for review" (contributor), "Publish" / "Update" / "Schedule" (when the date is in the future), "Preview" (autosave now, then open `/admin/preview/{id}` in a new tab), "Revisions (n)" → Task 10 route.
    - Save: first `saveEditedEntityRecord` for every `__experimentalGetDirtyEntityRecords()` entry (edited reusable blocks; a 403 there shows "You cannot edit reusable blocks; the post was not saved" and stops), then `PUT /items/{id}` with `serialize(blocks)` and `expectedModifiedAt`. 409 → "Someone else changed this post. Reload to see their version; your text stays in this tab." Success → notice "Published. The site updates in a few seconds."
    - Autosave every 60 s while dirty → `PUT /items/{id}/autosave`. `beforeunload` warning while dirty.
  - `Sidebar.tsx`: status and visibility (public or private), date picker (`DateTimePicker`, site zone via `@wordpress/date` `setSettings` with `GET /site`'s new `timezone` field), slug, categories (checkbox tree plus "Add new category"), tags (`FormTokenField`, new tags created on save), excerpt, author (editors and administrators: a select of users), discussion (comments open or closed), page parent and order (pages), and the `BlockInspector` tab.
  - `MediaLibrary.tsx`: `addFilter('editor.MediaUpload', 'stillpress/media', () => MediaLibrary)`. A modal with a grid from `GET /admin/api/media`, filtered by `allowedTypes`, an upload button and alt/title editing; it calls `onSelect({id, url, alt, mime, width, height})`.
  - `GET /admin/api/site` gains `timezone` and `permalinkStructure`.

- [ ] **Step 1: Install pins** — `cd src/main/webui && mise exec -- bun add --exact <the npm pins>`; Expected: `bun.lock` updated, `bun run typecheck` passes.
- [ ] **Step 2: Implement** the files above.
- [ ] **Step 3: Build** — `cd src/main/webui && mise exec -- bun run build`; Expected: PASS, with the editor in its own chunk (`dist/assets/Editor-*.js`) and the entry chunk under 300 KB.
- [ ] **Step 4: Run** `AdminShellTest` → PASS (routes `/admin/posts`, `/admin/edit/1` serve the SPA).
- [ ] **Step 5: Check in a browser** (dev mode via `quarkus-agent`, Playwright MCP), on the sample import:
  - Open a block post. A gallery shows its images in more than one column. Add a paragraph and a YouTube embed (preview through the proxy) and an uploaded image, then Publish; the static page shows them within 20 s.
  - Turn a paragraph into a reusable block in post A, edit it from post B as editor, save B; post A's static page shows the change.
  - As a contributor: no Publish button; "Submit for review" works; the post shows as Pending for the editor.
  - Schedule a post 2 minutes ahead; it goes live by itself.
  - Close the tab with unsaved edits after 70 s, reopen: the autosave notice appears.
- [ ] **Step 6: Commit and push** — `git commit -m "feat: Gutenberg editor screen with media library, autosave and preview"`.

---

### Task 8: Classic block for classic items

**Files:**
- Create: `src/main/webui/src/editor/classic.ts`
- Modify: `Editor.tsx`, `package.json` (`tinymce` 4.9.11)
- Test: `src/main/webui/src/editor/classic.test.ts` (`bun test`), browser check

**Interfaces:**
- Consumes: Task 7's editor.
- Produces:
  - `classic.ts`: `installClassicEditor(): Promise<void>` — lazy-imports `tinymce` 4.9.11 with its `modern` theme and the plugins `charmap`, `colorpicker`, `hr`, `lists`, `media`, `paste`, `tabfocus`, `textcolor`, `fullscreen` (WordPress's own `wordpress`/`wp*` TinyMCE plugins are PHP-shipped and not used), sets `window.tinymce`, and provides the `window.wp.oldEditor` shim (`getDefaultSettings()`, `initialize`, `remove`, `getContent`) that `core/freeform` calls.
  - `toBlocks(body: string, bodyFormat: 'classic' | 'blocks'): BlockInstance[]` — classic → `[createBlock('core/freeform', { content: body })]`, never `rawHandler` (no automatic conversion, spec §10). "Convert to blocks" is the freeform block's own toolbar button.
  - `bodyFormatOf(blocks: BlockInstance[]): 'classic' | 'blocks'` — `classic` when every top-level block is `core/freeform`, else `blocks`.
- [ ] **Step 1: Write failing tests**

```ts
import { expect, test } from 'bun:test';
test('classic body opens as one freeform block, unchanged', () => {
  const b = toBlocks('<p>Hi</p>\n\nSecond', 'classic');
  expect(b.map((x) => x.name)).toEqual(['core/freeform']);
  expect(b[0].attributes.content).toBe('<p>Hi</p>\n\nSecond');
});
test('format stays classic until a block is added or converted', () => {
  expect(bodyFormatOf(toBlocks('<p>Hi</p>', 'classic'))).toBe('classic');
  expect(bodyFormatOf([...toBlocks('<p>Hi</p>', 'classic'), createBlock('core/paragraph')])).toBe('blocks');
});
```

- [ ] **Step 2: Run, verify fail** — `cd src/main/webui && mise exec -- bun test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass**, `bun run build` → PASS.
- [ ] **Step 5: Browser check** — open a classic sample post: one Classic block with the TinyMCE toolbar; edit a word, Update; the stored body is still classic HTML (`GET /admin/api/items/{id}` `bodyFormat` `classic`), and the static page matches except for the word. "Convert to blocks" then Update → `bodyFormat` `blocks`.
- [ ] **Step 6: Commit and push** — `git commit -m "feat: classic block for classic items, convert to blocks on demand"`.

---

### Task 9: Reusable blocks screen

**Files:**
- Create: `src/main/webui/src/pages/Blocks.tsx`
- Modify: `main.tsx` (routes `/blocks`, `/blocks/:id`), `Editor.tsx` (factor the canvas and save bar into a component reused for a `wp_block` entity)
- Test: browser check

**Interfaces:**
- Consumes: `/admin/api/blocks` (Task 5), the editor canvas (Task 7).
- Produces:
  - `/blocks`: a list with title, "used by N items" and last modified; authors and up get "Add new"; a block opens for editing when `canEdit`, read-only otherwise.
  - `/blocks/:id`: the pattern editor bound to the `wp_block` entity. It saves via `PUT /admin/api/blocks/{id}` with `expectedModifiedAt`, notice "Saved. N items will be republished.", autosave and revisions as for items (`object_type` `reusable_block`).
  - In the post editor, a selected reusable block's toolbar gets "Edit original" → `/blocks/:id` (spec §6.2 item 3: synced patterns are locked inside posts).
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build** — `bun run build` → PASS.
- [ ] **Step 3: Browser check** — "Edit original" from a post opens the block, an edit there republishes both posts using it.
- [ ] **Step 4: Commit and push** — `git commit -m "feat: reusable blocks screen with edit original"`.

---

### Task 10: Revisions browser

**Files:**
- Create: `src/main/webui/src/pages/Revisions.tsx`
- Modify: `main.tsx` (routes `/edit/:id/revisions`, `/blocks/:id/revisions`), `package.json` (`diff` 9.0.0)
- Test: `src/main/webui/src/pages/revisions.test.ts` (`bun test`), browser check

**Interfaces:**
- Consumes: the revision endpoints of Tasks 4 and 5.
- Produces:
  - `compare(a: Rev, b: Rev): { title: Change[]; body: Change[] }` (exported, using `diffWords` for title, `diffLines` for the serialized body).
  - The screen: a slider or list of revisions (author, time). Side by side "from" and "to" with additions and removals marked (colour plus `+`/`−` markers, so meaning is not carried by colour alone). "Restore this revision" → `POST …/restore`, then back to the editor with the notice "Restored. The site updates in a few seconds." (for a published item).
- [ ] **Step 1: Write failing test**

```ts
test('compare marks a changed word only', () => {
  const c = compare({ title: 'Old title', body: 'a\nb\n' }, { title: 'New title', body: 'a\nc\n' });
  expect(c.title.filter((x) => x.added).map((x) => x.value.trim())).toEqual(['New']);
  expect(c.body.filter((x) => x.removed).map((x) => x.value)).toEqual(['b\n']);
});
```

- [ ] **Step 2: Run, verify fail; Step 3: implement; Step 4: run, verify pass** (`bun test`, `bun run build`).
- [ ] **Step 5: Browser check** — three saves, compare the first and the third, restore the first on a published post; its static page shows the restored text.
- [ ] **Step 6: Commit and push** — `git commit -m "feat: revisions browser with compare and restore"`.

---

### Task 11: Admin in English and Russian

**Files:**
- Create: `src/main/webui/src/i18n/index.ts`, `i18n/ru.json`, `i18n/wp-ru.json` (snapshot), `i18n/strings.test.ts`
- Modify: every `.tsx` page (user-visible strings through `__()`, `_n()`, `sprintf()` with domain `stillpress`), `main.tsx` (load the locale before the first render), `admin/AuthResource.java` (`GET /session` gains `locale`; `PUT /admin/api/session/locale` `{"locale":"en"|"ru"}` → 204, else 400), `Shell.tsx` (language switch), `mise.toml` (task `i18n:wp` that refreshes `wp-ru.json`)
- Test: `i18n/strings.test.ts`, `admin/AuthResourceTest.java` (locale)

**Interfaces:**
- Consumes: `@wordpress/i18n`.
- Produces:
  - `loadLocale(locale: 'en' | 'ru'): Promise<void>` — for `ru`, dynamic-imports `wp-ru.json` → `setLocaleData(..., 'default')` (Gutenberg) and `ru.json` → `setLocaleData(..., 'stillpress')`, then sets `<html lang>`. For `en`, nothing to load.
  - mise task `i18n:wp`: `curl -fsSL 'https://translate.wordpress.org/projects/wp/dev/ru/default/export-translations/?format=jed1x' -o src/main/webui/src/i18n/wp-ru.json`; the snapshot is committed and refreshed deliberately with Gutenberg upgrades.
  - The effective locale rule from Global Constraints lives in `AuthResource`'s session view.
- [ ] **Step 1: Write failing tests**

```ts
// strings.test.ts: scans src/**/*.tsx for __('…', 'stillpress'), _x, _n(singular, plural…) literals
test('every stillpress string has a Russian translation', () => {
  expect(missingFrom(ruJson, extracted())).toEqual([]);
});
test('no Russian entry is orphaned', () => { expect(orphansIn(ruJson, extracted())).toEqual([]); });
```

```java
// AuthResourceTest
@Test void localeDefaultsFromSiteLanguageAndCanBeChanged() {
  // site language ru-RU → GET /session locale "ru"; PUT locale "en" → "en"; PUT "de" → 400
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement**; run `mise run i18n:wp` once.
- [ ] **Step 4: Run, verify pass** (`bun test`, `AuthResourceTest`, `bun run build`).
- [ ] **Step 5: Browser check** — switch to Русский: the shell, the editor's inserter ("Добавить блок") and the date picker are Russian; switch back.
- [ ] **Step 6: Commit and push** — `git commit -m "feat: admin in English and Russian, per-user language"`.

---

### Task 12: 02a carry-overs

**Files:**
- Modify: `main.tsx`, `Shell.tsx`, `pages/Dashboard.tsx`, `pages/Import.tsx`, `pages/Users.tsx`, `Field.tsx`, `api.ts` (exported type guard), `admin/UsersResource.java`, `admin/SetupResource.java`, `store/AuthorRepository.java`
- Test: `admin/UsersResourceTest.java`, `admin/SetupResourceTest.java`, browser check

**Interfaces:**
- Produces:
  - Route guard by role: `Gate` re-checks on every navigation (`ready` resets when the path changes). A route the role cannot use (`/users`, `/import` for non-administrators, `/pages` and `/blocks` editing per `Capabilities`) redirects to `/` and is not in the nav. The dashboard's 401 goes to `/login?next=…`.
  - Stale responses ignored: each page's effect uses an `AbortController` aborted on cleanup.
  - Field errors announced: the error text has `role="alert"`, and focus moves to the first invalid field on submit.
  - Users create: login trimmed, 1–60 characters, `^[A-Za-z0-9 _.\-@]+$`; email trimmed and at most 100 characters; display name trimmed and at most 250 → 400 with `field`. A duplicate email → 409 `{"error":"email_taken"}`. A unique-constraint violation in a race → 409 `login_taken` (caught `DataAccessException` with SQL state `23505`).
  - Setup upgrade path: an existing row with the typed login also gets the typed email and display name.
  - Import screen keeps polling while `state` is `running` even when `error` says the import runs in another process; it shows that text and keeps refreshing.
  - `api.ts` exports `isApiError(e: unknown): e is ApiError`; every `catch` uses it; `Import.tsx` narrows with a local `const s = status` instead of `status!`.
- [ ] **Step 1: Write failing tests**

```java
@Test void login61CharactersIs400NotA500() { }
@Test void duplicateEmailIs409() { }
@Test void concurrentSameLoginGivesOne201AndOtherwise409() { /* 8 parallel creates of "dup" → containsOnlyOnce(201), rest 409 */ }
// SetupResourceTest
@Test void upgradeKeepsTypedEmailAndDisplayName() { }
```

- [ ] **Step 2: Run, verify fail; Step 3: implement; Step 4: run, verify pass.**
- [ ] **Step 5: Browser check** — as an author, typing `/admin/users` lands on the dashboard; a wrong set-password submission focuses the field and a screen reader announces the error (Playwright: the error element has `role=alert`).
- [ ] **Step 6: Commit and push** — `git commit -m "fix: role-aware admin routes, user validation, announced errors (02a carry-overs)"`.

---

### Task 13: Acceptance on the real blog through nginx (manual)

**Files:** results note `docs/superpowers/plans/2026-11-04-stillpress-02b-results.md`; `ROADMAP.md` (02b done; carried-over items; replica notes below).

- [ ] **Step 0: Blog from scratch through nginx** — a second fresh data directory (`data-02b-empty`): wizard, "Start empty", Settings (title, tagline, Day and name). Write two posts with an uploaded image and a category, and one page. Through nginx: home, both post URLs, the category and author archives, `/feed/`, the page. Expected: all 200 with the right content, and the posts' URLs follow the chosen structure.
- [ ] **Step 1: Fresh install through nginx**, as in 02a Task 7 (wizard, import `blog.xml` with media limit 100, posts per page 30, per feed 50). Expected: the inferred `permalink_structure` is `/%year%/%monthnum%/%day%/%postname%/`, and the timezone is the browser's.
- [ ] **Step 2: Republish timing** — on the dashboard, "Republish site": progress moves, and the time is recorded. Then edit one post's title and record the incremental publish time and the number of files written (log line). Expected: incremental is under 10 % of the full time.
- [ ] **Step 3: Hands-on** (spec §12.1 item 5, the 02b part):
  - Write a new post with a gallery of three uploaded images and a YouTube embed, publish, and open it through nginx.
  - Edit a classic post in the Classic block.
  - Schedule a post 3 minutes ahead and watch it appear.
  - Restore a revision.
  - Switch the admin to Russian.
- [ ] **Step 3b: Reset then import** — on the Step 0 scratch site: Settings → Reset site; the posts, uploaded image and page are gone from nginx; the dashboard offers Import; import `blog.xml` and it succeeds. The CLI `reset` without `--yes` deletes nothing.
- [ ] **Step 4: Parity unchanged** — `verify-urls --source https://blog.asm0dey.ru --target http://localhost:18083`. Expected: the same pass count as 02a (830), plus the new post's URL.
- [ ] **Step 5: Write the results note and update `ROADMAP.md`.** Add under the hardening backlog "Replicas (PostgreSQL only)": the in-memory login limiter and import progress are per process; the output of the publishing node must be synced to the others by an external tool; CLI `publish` beside a live server is now safe (row lock). Commit and push.

---

## Self-review notes

- Roadmap coverage for 02b: editor (T7), WP REST slice incl. method override, `canUser` and unknown-call logging (T5), classic block with on-demand conversion (T8), reusable blocks with the separate pattern editor and dirty-entity saving (T5, T7, T9), media library and upload (T6, T7), statuses and scheduling (T3, T4), incremental and background republish with dependency tracking and the fragment/hard-link design (T2, T3), preview (T4), revisions and autosave with compare, restore and 25 kept (T4, T10), admin i18n (T11), every 02a item labelled 02b (T6 nginx split and body-limit exclusion, T12 the rest).
- Decided with the user at plan time: db-scheduler for publishing jobs (replicas on PostgreSQL stay possible; H2 means single node; static output is synced between replicas by external tools).
- Calls made here (reviewable): `@wordpress/block-editor` hosted by our own screen rather than `@wordpress/editor` (the spike's proven path; `@wordpress/editor` would need most of the WordPress posts, settings, taxonomies and statuses REST API). Reusable blocks have owners: authors create and edit their own, editors and administrators edit any, contributors only insert (WordPress maps `wp_block` to post capabilities the same way). A slug change on a published item moves its permalink and leaves a 301, as WordPress's old-slug redirect does.
- Reset to experiment and then import (user request at plan review): Task 6d.
- Import closed for sites started empty (user request at plan review): Task 1b, the Global Constraint, Review Focus 5, `ImportClosedTest` in Task 6c.
- Back office parity (user request at plan review): Task 6c plus the item fields in Tasks 4 and 7, and the public-id rule in Global Constraints.
- From scratch (user request at plan review): Task 6b (settings screen, lazy default category, automatic excerpts) and Task 13 Step 0.
- Left for later: theme and appearance settings (plan 03), layout/global-styles CSS generator (plan 03; T7 only passes layout settings to the editor), episodes in the editor (plan 05), comment moderation (plan 04), email "pending review" notifications (02c).
