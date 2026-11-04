# Stillpress 02a — results on the real blog through nginx (Task 7)

Run on 2026-11-04 at commit `049cd3b`, against blog.asm0dey.ru, with a fresh data directory (`./data-02a`).

## Setup

| | |
|---|---|
| Stillpress | `serve` on :8080, `STILLPRESS_IMPORT_MEDIA_LIMIT=100` (same cap as plan 01's `--media-limit 100`), `STILLPRESS_TRUSTED_PROXIES=172.17.0.0/16` (nginx runs in a Docker container, so it connects from the bridge network, not from `127.0.0.1`) |
| nginx | `nginx:1.29-alpine` on :18083, `docs/deploy/nginx-site.conf.example` as the site config, include from `nginx-config --root /site/output/current --upstream host.docker.internal:8080`, `data-02a/output` mounted read-only |

Every browser step went through nginx (`http://localhost:18083`).

## Results

| Step | Result |
|---|---|
| Wizard | `/wp-admin` → 302 `/admin/` → `/admin/setup`. The setup token from the log, login `asm0dey`, language `ru-RU` created the site; "Import from WordPress" led to the import screen. |
| Import | `blog.xml` uploaded with posts per page 30 and items per feed 50, ran as a background job with live progress, then published. Report: **3,679 items, 15 comments, 100 media files (3,553 skipped by the limit), 0 reusable blocks**; unknown blocks, unknown shortcodes and skipped post types are the same lists as plan 01. |
| Authors | Two accounts: `asm0dey` (the wizard's administrator, `hasPassword: true`) and `marimarco` (author, `hasPassword: false`). The imported `asm0dey` merged into the administrator; no duplicate. Both author archives were published. |
| Second import | The Import screen shows the finished report and no form. `POST /admin/api/import` → 409 `already_imported`. `import --wxr blog.xml` on the CLI → "an import is already done", exit 1. |
| Imported author | Set-password link for `marimarco`, opened in a separate browser context: password set, login through `/wp-login.php` works, the nav shows no Users item, `GET /admin/api/users` → 403. |
| Rate limit | Throwaway contributor `ratetest`: attempts 1–5 with a wrong password → 401 "The login or password is incorrect."; attempt 6 → 429 (`Retry-After: 898`) "Too many attempts. Wait a few minutes and try again."; the correct password right after is refused too. `asm0dey` from the same IP still logs in (204). |
| Serving | Static pages come from nginx (`Server: nginx`, `Cache-Control: public, max-age=300`); `/feed/` is `application/rss+xml` with 50 items; pagination ends at `/page/4/`. |

## URL parity through nginx

`verify-urls --source https://blog.asm0dey.ru --target http://localhost:18083`: **3,218 URLs checked, 830 pass, 2,388 mismatches** — the same numbers as plan 01 against Mode 1, so nginx serving changes no result.

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

Every row matches plan 01's count exactly; there is no new cause.

## Problems found

| Problem | Detail | Owner |
|---|---|---|
| `GET /admin` (no trailing slash) → 404 | Both through nginx and directly on :8080 it answers the site's 404 page. `/admin/`, `/admin/users`, `/admin/login` are 200. The admin's own "Stillpress" nav link and the post-login redirect point at `/admin`, so a reload there (or typing the address) shows the site's 404. Quinoa's `ui-root-path=/admin` serves only `/admin/`. | 02b (first task touching the shell): redirect `/admin` → `/admin/`. |
| Set-password links use the imported site URL | After the import the link is `https://blog.asm0dey.ru/admin/set-password?token=…`. Before cutover that host is still WordPress, so the link must be edited by hand to the Stillpress host. | 02c, with invites and email reset (same link builder). |
| Author on `/admin/users` | An author who types the address gets "Could not load the users." plus the Add user form; the server correctly answers 403. Cosmetic. | 02b (role-aware screens). |
| `nginx-config --help` | Prints "Unknown option: '--help'" before the usage. No command has `--help` wired. | Hardening backlog. |
| Proxy trust in Docker | The README tells the reader to set `STILLPRESS_TRUSTED_PROXIES` when nginx is elsewhere; the example config does not mention it, and with Docker it is always needed. | Docs, with the next README touch. |

## Not checked here

Full media download (capped at 100 by decision), TLS, and the CLI password reset (covered by Task 2's tests).
