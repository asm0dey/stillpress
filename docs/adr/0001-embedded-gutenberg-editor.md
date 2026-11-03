# Embed the real Gutenberg editor; Java owns storage, rendering and publishing

The admin hosts WordPress's standalone `@wordpress/*` editor packages instead of a home-grown block editor (TipTap/ProseMirror or Editor.js). Imported WordPress content is Gutenberg block markup, static blocks carry their own final HTML (so look-parity with the source sites is cheap), and reusable blocks, galleries, embeds and columns come built in. A throwaway spike confirmed it runs without PHP.

## Consequences

- Quarkus must emulate a slice of the WordPress REST contract (`wp/v2/blocks`, `wp/v2/types`, `wp/v2/themes`, `oembed/1.0/proxy`, media, users), including apiFetch's `POST` + `X-HTTP-Method-Override`.
- The app must generate the layout/global-styles CSS WordPress produces in PHP.
- Gutenberg packages are pinned; upgrades are deliberate because saved block HTML can change between versions.
