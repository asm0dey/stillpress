# 02b follow-up: incremental publish cost — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A one-post incremental publish on the real blog takes under 10 % of a full publish (today about 25 %: 228–260 ms against 1,012 ms), and Mode 1 answers conditional GETs with 304.

**Architecture:** First measure: `SitePublisher` logs one line of per-phase timings per publish, and an opt-in benchmark test runs the real blog's import, a full publish and three one-post incrementals. Task 2 is a checkpoint where the user reads the numbers. If feeds dominate (the leading hypothesis), Task 3 caches one RSS `<item>` per post in the fragment cache, next to the archive entry, with the same invalidation, so a feed is concatenated instead of re-rendering up to `posts_per_rss` post bodies. Task 4 (independent of the numbers) adds `ETag`/`Last-Modified` and 304 to `StaticSiteRoute` through Vert.x's own `RoutingContext` freshness support.

**Tech Stack:** existing stack (Java 25, Quarkus, jOOQ, Flyway, H2/PostgreSQL, Qute, Vert.x Web). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-03-wordpress-static-cms-design.md` (§6.2 item 8, §6.2.1, §12), `docs/superpowers/plans/ROADMAP.md` ("02b follow-up (before 02c): incremental publish cost"), `docs/superpowers/plans/2026-11-04-stillpress-02b-results.md` (Step 2 timings, Problems row 3).

## Global Constraints

- Everything in the Global Constraints of plans 01, 02a and 02b still holds (package root `site.asm0dey.stillpress`, mise, portable Flyway SQL in `src/main/resources/db/migration/common`, jOOQ codegen via `mise exec -- mvn -q -Pcodegen generate-sources`, atomic publish, byte-identical output).
- Work on `main`; push every commit to `origin`.
- Binding precedents (copy into every implementer and reviewer prompt): AssertJ only, asserting on values (`containsExactly`, `hasSize`, `isEqualTo`), never `assertThat(boolean).isTrue()` for something that has a value; Hamcrest only inside RestAssured `.body()`. Set-based SQL: one statement for bulk work, no per-row loops. No production interface or wrapper that exists only for tests. Framework capabilities before own code. Trust jOOQ: no SQL probes. Read jOOQ results by field, never by position. JDK imports are `import module java.base;`. Quarkus docs via `quarkus_searchDocs`; tests via the `quarkus-agent` dev-mode tools (`devui-testing_runTest {"className":"..."}`), Maven only for spotless, codegen, package, the benchmark in Tasks 2 and 5, and the final `verify`.
- **Incremental == full, byte for byte.** Every `IncrementalPublishTest.incrementalEqualsFull*` test stays green after every task.
- **Published bytes do not change.** Feeds, pages and listings are byte-identical to what the current code writes for the same data (WordPress parity was checked against these bytes in 01–02b).
- Target from 02b: one-post incremental < **10 %** of a full publish, measured on `blog.xml` (the 13.6 MB WXR used in 01–02b acceptance; not in the repo, the user supplies its path) with posts per page **30**, per feed **50**.

## Review Focus

1. **Stale feed item after an edit that is not an item save.** Renaming an author, renaming a category or tag, or editing a reusable block changes a post's feed `<item>` without saving the post. Expected: the feed shows the new value, same as a full publish. Covered by the existing `incrementalEqualsFullAfterServiceSave` cases `display name` and `block save`; Task 3 adds `incrementalEqualsFullAfterTermRename`.
2. **Feed bytes drift when the template is split.** Whitespace between `<item>` elements, or HTML escaping applied twice to a cached fragment. Expected: byte-identical feed. Task 3 test `feedBytesUnchangedByFragments` (golden file captured before the change).
3. **304 for a file a publish just replaced.** A feed rewritten within the same second, same size, must not keep its ETag. Expected: 200 with the new body. Task 4 test `republishedFileLosesItsETag` (the ETag includes the file key: every written file is a new inode, hard-linked unchanged files keep theirs).
4. **Validators on the 404 page or on redirects.** A 404 page answered 304 would hide a later-published page. Expected: no `ETag` on 404s or redirects. Task 4 test `notFoundHasNoValidators`.
5. **Upgrade with an old fragment cache.** The V6 migration drops and recreates `item_fragment`. Expected: the next incremental renders the missing fragments, no error. Covered: `entries()` already renders missing fragments; Task 3 test `incrementalWithEmptyFragmentCacheEqualsFull`.

---

## File Structure

- `src/main/java/site/asm0dey/stillpress/publish/SitePublisher.java` — phase timings (Task 1); feed fragments (Task 3).
- `src/test/java/site/asm0dey/stillpress/publish/PublishCostBench.java` — opt-in benchmark on a real WXR (Task 1, run in Tasks 2 and 5).
- `src/main/resources/db/migration/common/V6__feed_fragment.sql`, `src/main/java/site/asm0dey/stillpress/store/FragmentRepository.java`, `src/main/resources/templates/feed/rss2.xml`, new `src/main/resources/templates/feed/rss2-item.xml` (Task 3).
- `src/main/java/site/asm0dey/stillpress/serve/StaticSiteRoute.java`, `src/test/java/site/asm0dey/stillpress/serve/StaticSiteRouteTest.java` (Task 4).
- `docs/superpowers/plans/2026-11-14-stillpress-02b-followup-results.md`, `docs/superpowers/plans/ROADMAP.md` (Tasks 2 and 5).

---

### Task 1: Per-phase publish timings and the benchmark

**Files:**
- Modify: `src/main/java/site/asm0dey/stillpress/publish/SitePublisher.java` (`run`, `render`, `Out`)
- Create: `src/test/java/site/asm0dey/stillpress/publish/PublishCostBench.java`

**Interfaces:**
- Produces: one INFO log line per publish, after the existing `Published %d files…` line:
  `Publish phases (incremental|full): fragments X ms, snapshot X ms, link untouched X ms, items X ms, listing pages X ms, feeds X ms, comments feed X ms, static+media X ms, activate X ms, record X ms`
  Phase boundaries: `fragments` = the `fragments.delete/clear` call in `run`; `snapshot` = `render` from `views.site()` through building `out.rows` (summaries, listings, bases); `link untouched` = `linkUntouched`; `items` = the item-page loop; `listing pages` = all `paged(...)` calls summed; `feeds` = all `feed(...)` calls summed; `comments feed`; `static+media` = the rest of `render`; `activate` = `switcher.activate`; `record` = `record(out)`.
- Produces: `PublishCostBench`, which prints `full: N files, M ms` and `incremental k: N files, M ms (P % of full)` for k = 1..3.

- [ ] **Step 1: Add the timings.** A `private static final class Phases` inside `SitePublisher`: `void add(String name, long startNanos)` adds `System.nanoTime() - startNanos` to a `LinkedHashMap<String, Long>` (summing repeated names), and `toString()` renders `name X ms, …` in insertion order. One instance lives on `Out` (`out.phases`). Log it at INFO in `publish(...)` next to the existing line. No test: it is a log line; Step 4 checks it.

- [ ] **Step 2: Write `PublishCostBench`.** `@QuarkusTest`, `@EnabledIfEnvironmentVariable(named = "STILLPRESS_BENCH_WXR", matches = ".+")`, one test `incrementalCostOnRealBlog()`:
  1. `store.reset()`; import the file at `System.getenv("STILLPRESS_BENCH_WXR")` with `importer.importWxr(in, 0, new ImportOptions(30, "category", "tag", 50))` (media limit 0: no downloads).
  2. `var full = publisher.publishAll();` once to warm up, then again for the measured full run.
  3. Pick the newest published post (`ITEM.TYPE = 'post'`, `ITEM.STATUS = 'publish'`, order by `PUBLISHED_AT` desc, limit 1). Three times: change its title (`"Bench " + k`), `r.store()`, `fragments.delete(List.of(id))`, `queue.item(id)` (the `edit` helper in `IncrementalPublishTest:297`), then `var batch = queue.snapshot().orElseThrow(); var inc = publisher.publish(batch); queue.done(batch);`.
  4. Print the lines above with `System.out.printf`. Assert `assertThat(inc.files()).isLessThan(full.files())` for each run.

- [ ] **Step 3: Run the existing publish tests.** `devui-testing_runTest {"className":"site.asm0dey.stillpress.publish.IncrementalPublishTest"}` and `…SitePublisherTest`. Expected: PASS; `PublishCostBench` reports skipped.

- [ ] **Step 4: Check the log line.** In the dev-mode test log (`quarkus_logs`), a `Publish phases (incremental): fragments … record … ms` line appears for each incremental publish of `IncrementalPublishTest`, with all ten phases.

- [ ] **Step 5: Commit.**

```bash
mise exec -- mvn -q spotless:apply
git add src
git commit -m "perf: log per-phase publish timings; opt-in benchmark on a real WXR"
git push
```

---

### Task 2: Measure on the real blog — CHECKPOINT

**Files:**
- Create: `docs/superpowers/plans/2026-11-14-stillpress-02b-followup-results.md`

- [ ] **Step 1: Run the benchmark.** Ask the user for the path to `blog.xml`. Then:

```bash
STILLPRESS_BENCH_WXR=/path/to/blog.xml mise exec -- mvn -q test -Dtest=PublishCostBench -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, with the `full:` and three `incremental k:` lines, and a `Publish phases` line for each run in the output.

- [ ] **Step 2: Write the results file.** A "Before" table: one row per run (full, incremental 1–3), columns: files, total ms, % of full, then one column per phase. Below it, one line naming the phase that takes the largest share of the incremental runs.

- [ ] **Step 3: Commit** the results file (`docs: 02b follow-up baseline timings`), push.

- [ ] **Step 4: STOP and ask the user.** Show the table. Ask one question (AskUserQuestion):
  - **If `feeds` is the largest incremental phase:** "Feeds take X of Y ms. Go ahead with Task 3 (cached feed items)? (Recommended)" / "Something else first".
  - **If another phase is largest:** name it, and propose the matching fix from the roadmap's candidates (`link untouched` → avoid the per-file walk; `snapshot` → narrow the whole-site reads; `listing pages` → re-render only the pages whose entries changed when the order did not). On approval, rewrite Task 3 in this file before dispatching it. Task 4 does not wait for this answer.

---

### Task 3: Cached feed items

Only as written if Task 2's checkpoint chose feeds.

**Files:**
- Create: `src/main/resources/db/migration/common/V6__feed_fragment.sql`
- Modify: `src/main/java/site/asm0dey/stillpress/store/FragmentRepository.java`
- Modify: `src/main/java/site/asm0dey/stillpress/publish/SitePublisher.java` (`entries`, `feed`)
- Create: `src/main/resources/templates/feed/rss2-item.xml`; Modify: `src/main/resources/templates/feed/rss2.xml`
- Test: `src/test/java/site/asm0dey/stillpress/publish/SitePublisherTest.java`, `src/test/java/site/asm0dey/stillpress/publish/IncrementalPublishTest.java`, new `src/test/resources/golden/home-feed.xml`

**Interfaces:**
- Produces: `FragmentRepository.ENTRY = "entry"`, `FragmentRepository.FEED = "feed"`; `Map<Long, String> find(String kind, Collection<Long> ids)`; `void put(String kind, Map<Long, String> htmlById)`. `delete(Collection<Long>)` and `clear()` keep their signatures and remove every kind, so the existing invalidation sites (`ItemService`, `TermService`, `UsersResource`, `SitePublisher.run`) need no change.
- Consumes: Task 1's `Phases` (the `feeds` phase must shrink).

- [ ] **Step 1: Capture the golden feed with the current code.** Add `SitePublisherTest.feedBytesUnchangedByFragments()`: after the sample import and `publishAll()`, read `output/current/feed/index.html` and assert it `isEqualTo` the contents of `src/test/resources/golden/home-feed.xml`. Create the golden file from the current code's output first (run the test once with the file missing, write the actual bytes to it, inspect them), then run the test. Expected: PASS against the unchanged code. Commit the golden file with the test.

- [ ] **Step 2: Write the failing tests.**
  - `IncrementalPublishTest.incrementalEqualsFullAfterTermRename`: like `incrementalEqualsFullAfterTermSlugChange`, but change only a category's **name** through `TermService`, then incremental vs full `isEqualTo`.
  - `IncrementalPublishTest.feedItemsAreCached`: after `publishAll()`, `fragments.find(FragmentRepository.FEED, List.of(itemId(5994)))` `hasSize(1)`, and its value `startsWith("<item>")`.
  - `IncrementalPublishTest.incrementalWithEmptyFragmentCacheEqualsFull`: `fragments.clear()`, `edit(5994, title)`, incremental vs full `isEqualTo`.
  Run: `devui-testing_runTest` for `IncrementalPublishTest`. Expected: `feedItemsAreCached` fails to compile/FAIL (no `find(String, …)`); the rename test may already pass (it guards the change).

- [ ] **Step 3: Migration V6.** The table is a cache that a missing row only costs a re-render, so drop and recreate it (portable on H2 and PostgreSQL, no PK alteration):

```sql
-- kind: 'entry' (archive list entry) or 'feed' (RSS <item>); a missing row is re-rendered on the next publish
DROP TABLE item_fragment;
CREATE TABLE item_fragment (
    item_id BIGINT NOT NULL REFERENCES item (id),
    kind VARCHAR(10) NOT NULL,
    html TEXT NOT NULL,
    PRIMARY KEY (item_id, kind)
);
```

Then `mise exec -- mvn -q -Pcodegen generate-sources`.

- [ ] **Step 4: `FragmentRepository`** — add the `kind` parameter to `find` and `put` (one statement each, filtered/inserted by `ITEM_FRAGMENT.KIND`); `delete` and `clear` unchanged in signature. Update the class Javadoc: "An item's rendered archive entry and feed item…".

- [ ] **Step 5: Templates.** Move the `<item>…</item>` element from `rss2.xml` into `feed/rss2-item.xml` (`{@site.asm0dey.stillpress.publish.views.ItemView item}`), unchanged. In `rss2.xml`, `items` becomes a `List<String>` of ready XML, emitted with `.raw` inside the same `{#for}` so the whitespace around each item is what it was. The golden test decides.

- [ ] **Step 6: `SitePublisher`.** Generalize `entries(slice)` into `private List<String> fragments(String kind, List<ItemRepository.Summary> slice, Function<ItemView, String> render)` (find, render the missing ones from `views(...)`, `put`, return in slice order). `entries` becomes `fragments(ENTRY, slice, v -> render("plain/entry", "entry", EntryView.of(v)))`. `feed(...)` takes the same newest-dated slice it takes today, gets `fragments(FEED, newest, v -> render("feed/rss2-item.xml", "item", v))`, and takes `lastBuildDate` from the first summary's `publishedAt()` (no view needed).

- [ ] **Step 7: Run.** `devui-testing_runTest` for `IncrementalPublishTest`, `SitePublisherTest`, `PublishJobsTest`. Expected: PASS, including `feedBytesUnchangedByFragments` and every `incrementalEqualsFull*`.

- [ ] **Step 8: Commit.**

```bash
mise exec -- mvn -q spotless:apply
git add src
git commit -m "perf: feeds concatenate cached per-post RSS items"
git push
```

---

### Task 4: Conditional GET in Mode 1

**Files:**
- Modify: `src/main/java/site/asm0dey/stillpress/serve/StaticSiteRoute.java` (`send`)
- Test: `src/test/java/site/asm0dey/stillpress/serve/StaticSiteRouteTest.java`

**Interfaces:**
- Produces: every 200 from `send` carries `Last-Modified` (the file's mtime) and `ETag` `"<hex mtime millis>-<hex size>-<hex fileKey().hashCode()>"` (the last part omitted when `fileKey()` is null). A GET or HEAD whose `If-None-Match` / `If-Modified-Since` match gets **304** with no body, keeping `Cache-Control` and `X-Content-Type-Options`. 404 pages and redirects get no validators.

- [ ] **Step 1: Write the failing tests.**
  - `feedAnswers304ToItsETag`: GET `/feed/` → 200, `ETag` header present; GET with `If-None-Match: <that etag>` → 304, empty body, `Cache-Control` `public, max-age=300`.
  - `answers304ToIfModifiedSince`: GET `/about/` → `Last-Modified`; GET with `If-Modified-Since: <that value>` → 304.
  - `otherETagGets200`: `If-None-Match: "nope"` → 200 with the page body.
  - `notFoundHasNoValidators`: GET `/no-such-page/` → 404, no `ETag` header (`header("ETag", nullValue())`).
  - `republishedFileLosesItsETag`: take `/feed/`'s ETag; change post 5994's title and `publisher.publishAll()`; GET with `If-None-Match: <old etag>` → 200, body contains the new title.
  Run: `devui-testing_runTest {"className":"site.asm0dey.stillpress.serve.StaticSiteRouteTest"}`. Expected: the 304 tests FAIL (no `ETag`).

- [ ] **Step 2: Implement in `send`.** For `status == 200` only: read `Files.readAttributes(file, BasicFileAttributes.class)`, set the validators with Vert.x's `ctx.lastModified(...)` and `ctx.etag(...)`, then `if (ctx.isFresh()) { res.setStatusCode(304).end(); return; }` before `sendFile`. Use the framework's freshness check, not a hand-written header comparison; confirm the method names against the Vert.x Web `RoutingContext` on the classpath. Add the one-line comment: `// shortcut: Last-Modified has 1 s resolution; a client sending only If-Modified-Since can miss a rewrite within the same second until max-age expires`.

- [ ] **Step 3: Run.** Same test class. Expected: PASS, and the existing `feedContentType`, `htmlCachedBriefly`, `mediaAndAssetsCachedForever` still pass.

- [ ] **Step 4: Commit.**

```bash
mise exec -- mvn -q spotless:apply
git add src
git commit -m "feat: Mode 1 answers conditional GETs with 304"
git push
```

---

### Task 5: Re-measure and close the follow-up

**Files:**
- Modify: `docs/superpowers/plans/2026-11-14-stillpress-02b-followup-results.md`, `docs/superpowers/plans/ROADMAP.md`

- [ ] **Step 1: Run the benchmark** (Task 2 Step 1 command). Expected: each incremental < 10 % of the full run.
- [ ] **Step 2: Results file.** Add an "After" table in the same shape as "Before", and one line per finding: target met or not, which phase moved.
  - If the target is still missed: say so with the numbers, list the next-largest phase, and stop for the user. Do not start another fix inside this task.
- [ ] **Step 3: ROADMAP.** Mark the "02b follow-up" item done with a pointer to the results file and the measured ratio; move anything not done (for example batching `ViewFactory`'s per-item queries, if still relevant) into the hardening backlog lines there.
- [ ] **Step 4: Full verify.** `mise exec -- mvn -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** (`docs: 02b follow-up results; roadmap`), push.

---

## Extension (added 2026-11-14, after Task 5 Step 2)

Task 5's measurement missed the target. A one-post publish takes 86–117 ms against a full publish of 548 ms: 16–21 %. The user chose to keep going for 10 %. Tasks 6–8 attack the phases that are left, and Task 9 closes the follow-up in place of Task 5's Steps 3–5. Numbers: `docs/superpowers/plans/2026-11-14-stillpress-02b-followup-results.md` ("After").

### Task 6: Prune old releases after the publish has finished

**Files:**
- Modify: `src/main/java/site/asm0dey/stillpress/publish/ReleaseSwitcher.java` (`activate`, `prune`)
- Modify: `src/main/java/site/asm0dey/stillpress/publish/SitePublisher.java` (`publish`)
- Test: `src/test/java/site/asm0dey/stillpress/publish/ReleaseSwitcherTest.java`

**Interfaces:**
- Produces: `ReleaseSwitcher.activate(Path)` swaps `current` only. New `public synchronized void prune()` deletes all but the newest `KEEP` (3) releases and never the one `current` points at. It keeps today's error handling: each release is tried on its own, and failures are logged, never thrown.
- `SitePublisher.publish(batch)` calls `switcher.prune()` after `status.finished(...)` and the `Published …` log line. It logs `Pruned old releases in X ms` at INFO, and `PublishResult.took()` does not include the prune. Both callers (`Jobs`, `PublishCommand`) already hold the publish lock around `publish`, so a prune never races with a release being built.

- [ ] **Step 1: Tests.** `activateDoesNotPrune`: five `activate`s leave five releases. `pruneKeepsNewestThreeAndLive`: then `prune()` leaves the newest three, and `current` still resolves. Move `pruneFailureNeverBreaksTheSwapOrDeletesTheLiveRelease` onto `activate` + `prune()`. Run `ReleaseSwitcherTest`. Expected: `activateDoesNotPrune` FAILS.
- [ ] **Step 2: Implement** the interfaces above.
- [ ] **Step 3: Run** `ReleaseSwitcherTest`, `SitePublisherTest`, `IncrementalPublishTest`. Run `PublishJobsTest` with `mise exec -- mvn -q test -Dtest=PublishJobsTest` (it takes longer than the dev-mode tool's 30 s timeout). Expected: PASS.
- [ ] **Step 4: Commit** `perf: prune old releases after the publish has finished`, spotless first, push.

### Task 7: Carry unchanged files over without a mkdir per file

**Files:**
- Modify: `src/main/java/site/asm0dey/stillpress/publish/SitePublisher.java` (`Out.hardLink`, `Out.write`, `linkUntouched`)

**Interfaces:**
- `Out` keeps a `Set<Path>` of directories already created in this release. `hardLink` and `write` call `Files.createDirectories(parent)` only for a parent not in the set. Today that call runs once per carried-over file (about 885 per incremental).
- Phases: split `link untouched` into `walk` (listing the live release and choosing files) and `link` (the `hardLink` calls).

- [ ] **Step 1: Implement the directory set and the phase split.** No new test. `untouchedFilesAreHardLinks` and every `incrementalEqualsFull*` already cover the behaviour.
- [ ] **Step 2: Run** `IncrementalPublishTest` and `SitePublisherTest`. Expected: PASS.
- [ ] **Step 3: Measure** with the benchmark command from Task 2 Step 1. Record `walk` and `link` in the report.
  - If `link` is still over 15 ms, hard-link the chosen files in parallel: the walk first builds the list, then `list.parallelStream()` runs `hardLink`, with each `IOException` wrapped in `UncheckedIOException` and unwrapped back into `IOException` by the caller. The directory set must then be concurrent: `ConcurrentHashMap.newKeySet()`, which tolerates two threads creating the same directory. Measure again.
  - Keep the parallel version only if it saves at least 5 ms. Say which way it went in the report.
- [ ] **Step 4: Commit** `perf: carry unchanged files over without a mkdir per file`, spotless first, push.

### Task 8: Carry the comments feed over when the batch cannot change it

**Files:**
- Modify: `src/main/java/site/asm0dey/stillpress/store/CommentRepository.java`, `src/main/java/site/asm0dey/stillpress/publish/SitePublisher.java` (`render`, `linkUntouched`)
- Test: `src/test/java/site/asm0dey/stillpress/publish/IncrementalPublishTest.java`

**Interfaces:**
- Produces: `boolean CommentRepository.anyApproved(Collection<Long> itemIds)`, one `fetchExists` statement on `COMMENT.ITEM_ID.in(ids)` and `COMMENT.STATUS.eq("approved")`.
- Rule: in an incremental publish whose batch items have no approved comment, the comments feed `/comments/feed/` is hard-linked from the live release instead of re-rendered. The site-wide comments feed only lists comments, and an item without comments has none to add, drop or retitle, whatever happens to it. Every other incremental, and every full publish, renders it as today.

- [ ] **Step 1: Tests.**
  - `commentsFeedCarriedOverForUncommentedEdit`: edit the title of a sample post that has no approved comment, publish incrementally, and assert the release's `comments/feed/index.html` is the live one's inode (`Files.isSameFile` against the previous release; compare with `isEqualTo(true)` on the value, as `untouchedFilesAreHardLinks` does). Then assert incremental equals full.
  - `incrementalEqualsFullAfterCommentedPostTitleEdit`: the same edit on a sample post that has approved comments; incremental `isEqualTo` full.
  - Pick both posts from the sample data by querying `COMMENT`.
  - Run `IncrementalPublishTest`. Expected: the carry-over test FAILS.
- [ ] **Step 2: Implement.** In `linkUntouched`, leave `comments/feed/index.html` out of `skip` when the rule says carry over, and have `render` skip `commentsFeed` in that case. Decide once per publish and keep the result on `Out`.
- [ ] **Step 3: Run** `IncrementalPublishTest`, `SitePublisherTest`. Expected: PASS.
- [ ] **Step 4: Commit** `perf: carry the comments feed over when the batch has no comments`, spotless first, push.

### Task 9: Re-measure and close

Task 5's Steps 3–5 apply as written, after one more benchmark run:

- [ ] **Step 1: Benchmark** (Task 2 Step 1 command). Add an "After Tasks 6–8" table to the results file in the same shape.
- [ ] **Step 2: Verdict.**
  - Target met: say so.
  - Not met: give the numbers and the largest phase left, and record the follow-up in the ROADMAP hardening backlog. Stop there. No further fix in this plan.
- [ ] **Step 3: ROADMAP, full verify, commit** (Task 5 Steps 3–5).
