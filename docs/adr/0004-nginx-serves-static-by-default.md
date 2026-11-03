# nginx serves the static site by default; Stillpress serving stays supported

The published site is plain files, so a dedicated web server should serve them: nginx does it with sendfile, caching and TLS tooling operators already run, and readers keep the site while Stillpress restarts or upgrades. The default deployment is therefore Mode 2: nginx's root points at `output/current`, and nginx falls back to Stillpress only for what is not a file (`try_files $uri $uri/ @stillpress`) and for dynamic paths (admin, login, preview, comment submission, legacy `?p=` URLs, podcast download counting). Stillpress generates the nginx include once; publishing never reloads nginx.

Mode 1 — Stillpress serves everything behind a TLS-only reverse proxy — stays a supported option for the simplest setups. It costs nothing extra: Stillpress must answer the fallback paths in Mode 2 anyway (redirect map, real 404 page), and that same route serves files in Mode 1.

## Consequences

- The author's sites run behind nginx in production; the cutover acceptance run goes through nginx.
- Redirects, the 404 page and legacy query URLs live in Stillpress in both modes; nginx config never contains site data, so it never changes on publish.
- Mode 1 is downtime-coupled to Stillpress: the site is down while the app is down.
