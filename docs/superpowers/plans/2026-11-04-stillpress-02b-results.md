# Stillpress 02b — results on the real blog through nginx (Task 13)

Run on 2026-11-04 from commit `5445cf8`, with two fixes made during the run (`6bc434a`, `6924417`). The target was blog.asm0dey.ru, and each site got a fresh data directory: `./data-02b-empty` for the blog built from scratch and `./data-02b` for the import.

## Setup

| | |
|---|---|
| Stillpress | `serve` on :8080, `STILLPRESS_IMPORT_MEDIA_LIMIT=100` (the same cap as 02a), `STILLPRESS_TRUSTED_PROXIES=172.17.0.0/16` (nginx runs in Docker and connects from the bridge network) |
| nginx | `nginx:1.29-alpine` on :18083, `docs/deploy/nginx-site.conf.example` as the site config, include from `nginx-config --root /site/output/current --upstream host.docker.internal:8080`, the data directory's `output/` mounted read-only |
| Browser | Playwright (Chrome 157) with the system locale `ru-RU` and time zone `Europe/Berlin` |

Every browser step went through nginx (`http://localhost:18083`). Create `output/` before starting the container. If it is missing, Docker creates it as root and Stillpress cannot publish into it.

## Results

| Step | Result |
|---|---|
| 0. Blog from scratch | Wizard → "Start empty" → Settings (title "Scratch Blog", a tagline, structure "Day and name"). I wrote two posts, each with an uploaded image and the new category "Travel", and a page "About". Through nginx, all of these returned 200 with the right content: home, `/2026/11/04/hello-from-scratch/`, `/2026/11/04/second-post-a-day-in-berlin/`, `/category/travel/`, `/author/asm0dey/`, `/feed/` (`application/rss+xml`, both posts), `/about/` and both `/media/2026/11/*.jpg`. Post URLs follow `/%year%/%monthnum%/%day%/%postname%/`. **Pass**, after fixing the cached admin shell (Problems, row 1). |
| 1. Fresh install + import | Wizard (`ru-RU`) → "Import from WordPress", `blog.xml` with posts per page 30 and per feed 50. Report: **3,679 items, 15 comments, 100 media files (3,553 skipped by the limit), 0 reusable blocks**. The unknown blocks, unknown shortcodes and skipped post types are the same lists as in 02a. Settings afterwards: `permalink_structure` = `/%year%/%monthnum%/%day%/%postname%/` (inferred), `timezone` = `Europe/Berlin` (the browser's). **Pass.** |
| 2. Republish timing | Dashboard "Republish site": the card went "Changes are waiting" → "Publishing… 800 of about 908 files" → "The site is up to date". **Full: 1,008 files in 1,012 ms.** A one-post title edit, published incrementally three times: **21 files in 258 / 260 / 228 ms**, which is about **25 %** of the full time. **Fails the < 10 % target** (Problems, row 3). |
| 3. Hands-on | All five checks pass. (a) A new post with a gallery of three uploaded images and a YouTube embed was published, and through nginx it shows the three images (800 px each) and the YouTube facade, which turns into a `youtube-nocookie.com` iframe on click. (b) Classic post "Прага. День пятый" opened as one Classic block. I edited it in TinyMCE (bold text at the start) and saved; it stayed `classic`, and nginx serves the edit. (c) A post scheduled for 20:51 returned 404 until 20:51:00, then went live by itself (incremental publish, 15 files) and appeared on the home page. (d) Restoring the oldest of three revisions of a post asked for confirmation, brought back its title and reported "Restored. The site updates in a few seconds." (e) The language switch put the admin in Russian, including Gutenberg's own strings in the editor (`author.locale` = `ru`). |
| 3b. Reset, then import | Scratch site: `reset` on the CLI without `--yes` listed the rows (4 items, 2 media, 1 term…) and exited 1; nothing was deleted (the post still returned 200, and both media files were still on disk). Settings → Reset site (password plus the title) returned 204. Through nginx the posts, page, category and `/media/2026/11/red.jpg` then returned 404, and the home page 200. After a reload the dashboard showed Import. `blog.xml` imported with the same report as Step 1, and the publish that followed wrote 1,008 files. **Pass.** |
| 4. Parity | See below. |

## URL parity through nginx

`verify-urls --source https://blog.asm0dey.ru --target http://localhost:18083`, run after all the Step 3 edits: **3,218 URLs checked, 830 pass, 2,388 mismatches**. These are the same numbers as 02a and plan 01, so the editing, the scheduled post and the incremental publishes changed no existing URL.

| Count | Cause (plan 01 row) | Owner |
|---:|---|---|
| 925 | Attachment pages `/<post>/<attachment>/` redirect to the parent | Plan 04 |
| 925 | Their feeds → 404 | Plan 04 |
| 211 | Unattached attachment pages → 404 | Plan 04 |
| 211 | `?attachment_id=N`, unattached → 404 | Plan 04 |
| 106 | `?attachment_id=N`, attached → parent | Plan 04 |
| 4 | Post-format archives `/type/<format>/` | Plan 04 |
| 3 | `/page/1/` variants | Small fix (already listed) |
| 3 | `/index.php/…` permalinks | Small fix (already listed) |

Every row matches 02a's count exactly; there is no new cause. The crawler starts from the live site, so it never sees the posts written in this run. I checked those through nginx directly, and each returned 200: `/2026/11/04/приёмка-02b-галерея-и-видео/` (gallery and video), `/2026/11/04/запланированная-запись-02b/` (the scheduled post), and the edited posts at their unchanged addresses. Their shortlinks `?p=6107` and `?p=6108`, the next WordPress ids after the imported ones, both give a 301 to those addresses.

## Problems found

| Problem | Detail | Owner |
|---|---|---|
| The admin shell was cached as immutable for a day | `/admin/` and every SPA route went out as `Cache-Control: public, immutable, max-age=86400`. A browser that had opened the admin before an upgrade kept the old `index.html` and its old bundles: the wizard's POST went out without `X-CSRF-TOKEN` and got a bare 400, and the form showed no error. | **Fixed in this run** (`6bc434a`): HTML under `/admin/` is `no-cache`, and hashed assets keep their caching. Test: `AdminShellTest.shellIsRevalidatedButAssetsStayCached`. |
| Dashboard "Posts and pages" counted attachments | The imported blog showed 3,681; the real figure is 105 (posts and pages outside the trash). | **Fixed in this run** (`6924417`); asserted in `ImportResourceTest`. After a restart the dashboard showed 105. |
| Incremental publish is about 25 % of a full one, not < 10 % | A title change dirties every listing the post appears in, and each listing re-renders all of its `/page/N/` pages and its feed: here home (4 pages and a 50-item feed), the author archive, the category and the date archives. Hard-linking the unchanged files costs about 24 ms. On this blog (about 90 published posts, 1,008 files) the listings are a large share of the full run, so the ratio is high. The full time grows with the number of posts, while one listing's re-render does not, so the ratio falls on bigger sites. The plan's expectation still fails here. A fix is to re-render only the pages whose entries changed when the order did not, and to build feed items from fragments. | Hardening backlog |
| Just-uploaded images are 404 in the editor for a moment (Mode 2) | The upload response points at `/media/YYYY/MM/<name>`, which nginx serves from the published release. Until the media publish lands (about 100 ms), the editor's `<img>` is 404. One of three gallery images stayed broken in the canvas until the page was reloaded; the published post was fine. | Hardening backlog |
| The Classic block drops `&nbsp;` spacer paragraphs | Editing a classic post in TinyMCE removed three `&nbsp;` paragraphs and collapsed the blank lines around one `<p class=…>`; WordPress keeps them. The text and the shortcodes were unchanged. | Hardening backlog |
| The import report is English in a Russian admin | The report text ("Imported 3679 items…, Unknown blocks…") is written by the server in English. | Hardening backlog (with the other server-written texts in the ledger) |
| The Import link appears only after a reload following Reset | After Settings → Reset site, the SPA navigates to the dashboard, and the nav shows Import only after a reload. | Hardening backlog |
| Home page `<title>` repeats the site title | "Scratch Blog - Scratch Blog"; WordPress shows "Title – Tagline". | Plan 03 (themes) |
| Editor console warnings | `Unknown WordPress API call: GET /wp/v2/media` (gallery, image) and `OPTIONS /wp/v2/settings` (embed). Both are known and listed in the ledger, and neither broke anything. | Hardening backlog |

During the run I first suspected two more problems; neither is a product bug. A page saved with an empty title and the slug `3` came from a stale Playwright script that clicked "Publish" on the next editor it found. I set the slug to `about`, and `/3/` now gives a 301 to `/about/`. The wizard showing in Russian is correct: the browser's language is `ru-RU`.

## Not checked here

Full media download (capped at 100 by decision), TLS, PostgreSQL (H2 only in this run), two replicas, and the CLI `publish` running beside the live server (covered by `PublishJobsTest`).
