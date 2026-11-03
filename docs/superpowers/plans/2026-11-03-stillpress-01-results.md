# Stillpress 01 — results on the real blog (Task 9)

Run on 2026-11-03 at commit `1bc071c`, against blog.asm0dey.ru.

## Import

`import --wxr blog.xml --media-limit 100 --posts-per-page 30 --posts-per-rss 50` (13.6 MB WXR export):

| | |
|---|---|
| Items | 3,679 (posts, pages, attachments) |
| Comments | 15 |
| Media downloaded | 100 (test limit) — 3,553 more skipped by the limit, 0 missing |
| Reusable blocks | 0 |
| Skipped post types | `nav_menu_item`, `custom_css`, `wp_global_styles`, `wp_navigation` |
| Unknown blocks | `meow-gallery/gallery` |
| Unknown shortcodes | `g-1`, `g-2`, `gallsery-1`, `label`, `lightbox`, `button`, `Group`, `ryn`, `wb_fb_f`, `pano` |

The first import attempt found 670 images linked through Jetpack's image CDN (`iN.wp.com/blog.asm0dey.ru/wp-content/uploads/…`). Those are now recognised as the blog's own uploads (commit `a2a751b`), so none are missing.

## Publish

1,008 files in 3.1 s. The home page has 30 posts and pagination ends at `/page/4/`, as on the live blog. `/feed/` has 50 items.

## URL parity

`verify-urls --source https://blog.asm0dey.ru --target http://localhost:18082`: **3,218 URLs checked, 830 pass, 2,388 mismatches.**

Every post, page, category, tag, author and date archive, every pagination page, and the site, category and comment feeds pass. Each mismatch falls into one of these causes:

| Count | Cause | Owner |
|---:|---|---|
| 925 | Attachment pages `/<post>/<attachment>/`: WordPress renders them (200); Stillpress redirects to the parent post. The spec allows both ("rendered or redirected to the parent post"), but the parity rule needs the same final URL. | Plan 04: render attachment pages, or teach `verify-urls` that a redirect to the attachment's parent is a pass. |
| 925 | Feeds of those attachment pages, `/<post>/<attachment>/feed/` → 404 on Stillpress. | Plan 04, together with the row above. |
| 211 | Unattached attachment pages `/<attachment-slug>/` → 404 (there is no parent to redirect to). | Plan 04: render attachment pages. |
| 211 | `?attachment_id=N` for unattached attachments → 404. | Plan 04. |
| 106 | `?attachment_id=N` for attached ones: WordPress renders the attachment page; Stillpress redirects to the parent. | Plan 04 (same decision as row 1). |
| 4 | Post-format archives `/type/<format>/` and their feeds. | Plan 04 (post formats). |
| 3 | `/page/1/` variants: WordPress answers 200; Stillpress has no page 1 URL. | Small fix: redirect `…/page/1/` to the archive root, which is what WordPress's canonical redirect does for browsers. |
| 3 | `/index.php/…` PATHINFO permalinks. | Small fix: strip a leading `/index.php` and redirect. |

All 2,388 mismatches come from features owned by later plans (attachment pages, post formats) or from two small redirect gaps. None of them is a post, page or listing that renders at the wrong URL.

## Not checked here

Visual fidelity, sitemaps and search belong to later plans: the theme comes in plan 03, and the cutover gate (spec §12.1) is planned for plan 04.
