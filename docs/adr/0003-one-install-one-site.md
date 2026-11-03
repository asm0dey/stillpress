# One install hosts exactly one site

Sites must be fully separate entities that know nothing about each other, and the author does not care whether several sites share a process. So each Stillpress install runs one site with its own data directory; several sites means several installs (e.g. one container each behind a reverse proxy). We rejected WordPress-multisite-style hosting (one process, Host-header routing, shared accounts or a network admin) because isolation would then depend on every query being scoped correctly, for a convenience nobody asked for.

## Consequences

- No single sign-on, cross-site user list or shared media — by design.
- Creating a site = starting an install; its first-run setup wizard (protected by a one-time token from the server log) creates the first administrator and optionally imports from WordPress.
- Backing up, moving or deleting a site = handling one data directory.
