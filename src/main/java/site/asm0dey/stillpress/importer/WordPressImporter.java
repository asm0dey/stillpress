package site.asm0dey.stillpress.importer;

import static site.asm0dey.stillpress.db.Tables.*;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.blocks.Block;
import site.asm0dey.stillpress.blocks.BlockParser;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.content.PermalinkStructure;
import site.asm0dey.stillpress.publish.Permalinks;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.MediaRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.wxr.*;

/**
 * One-shot WXR import: streams the export, downloads media, rewrites upload URLs and writes every row in one transaction.
 */
@ApplicationScoped
public class WordPressImporter {
    private static final Set<String> KNOWN_BLOCKS = Set.of("jetpack/tiled-gallery", "panorama360/flat-image");
    private static final Set<String> KNOWN_SHORTCODES = Set.of("caption", "gallery", "embed", "video", "audio");
    private static final Pattern SHORTCODE = Pattern.compile("\\[([A-Za-z][\\w-]*)(?=[\\s\\]/])");
    private static final Pattern SIZE_VARIANT = Pattern.compile("-\\d+x\\d+(?=\\.[^./]+$)");
    private static final Pattern DATED = Pattern.compile("^(\\d{4})/(\\d{2})/");
    private static final String UPLOADS = "/wp-content/uploads/";
    private static final Pattern PHOTON_PREFIX = Pattern.compile("//" + MediaRewriter.PHOTON);
    // no '&': WordPress strips it from upload names, and it would swallow a following &quot;
    private static final String URL_CHARS = "[^\\s\"'<>()\\[\\]\\\\?#&]+";
    private static final Pattern ANY_UPLOAD = Pattern.compile("(?:https?:)?//" + URL_CHARS + UPLOADS + URL_CHARS);
    @Inject
    DSLContext db;
    @Inject
    DataDir dataDir;
    @Inject
    MediaFetcher fetcher;
    @Inject
    ItemRepository items;
    @Inject
    MediaRepository media;
    @Inject
    SettingRepository settings;
    @Inject
    AuthorRepository authors;

    // a multi-GB export with downloads runs far past Narayana's 60 s default
    public ImportReport importWxr(InputStream in) throws AlreadyImportedException {
        return importWxr(in, Integer.MAX_VALUE);
    }

    public ImportReport importWxr(InputStream in, int mediaLimit) throws AlreadyImportedException {
        return importWxr(in, mediaLimit, ImportOptions.DEFAULTS);
    }

    public ImportReport importWxr(InputStream in, int mediaLimit, ImportOptions options) throws AlreadyImportedException {
        return importWxr(in, mediaLimit, options, ImportListener.NONE);
    }

    /**
     * @param mediaLimit download at most this many distinct originals; later ones stay at the source and get no row
     */
    @Transactional
    @TransactionConfiguration(timeout = 24 * 60 * 60)
    public ImportReport importWxr(InputStream in, int mediaLimit, ImportOptions options, ImportListener listener)
            throws AlreadyImportedException {
        if (items.count() > 0) {
            throw new AlreadyImportedException();
        }
        var run = new Run(mediaLimit, options, listener);
        WxrReader.read(in, run);
        run.finish();
        return run.report();
    }

    private record PendingTerm(long id, String taxonomy, String parentSlug) {}

    private record PendingItem(long id, long sourceId, String type, String permalink, long parentSourceId) {}

    private final class Run implements WxrHandler {
        private final int mediaLimit;
        private final ImportOptions options;
        private final ImportListener listener;
        private final Set<String> skippedMedia = new LinkedHashSet<>();

        Run(int mediaLimit, ImportOptions options, ImportListener listener) {
            this.mediaLimit = mediaLimit;
            this.options = options;
            this.listener = listener;
            // users created before the import (the wizard's administrator) keep their slugs
            authorSlugs.addAll(authors.slugs());
        }

        private String homeScheme = "https", homeAuth = "", siteAuth = "";
        private Pattern uploadUrl;
        private final Set<String> authorSlugs = new HashSet<>();
        private final Map<String, Long> authorIds = new HashMap<>();
        private final List<PendingTerm> pendingTerms = new ArrayList<>();
        private final List<PendingItem> pendingItems = new ArrayList<>();
        private int itemCount, commentCount, mediaCount, reusableCount;
        private final Set<String> unknownBlocks = new LinkedHashSet<>();
        private final Set<String> unknownShortcodes = new LinkedHashSet<>();
        private final Set<String> missingMedia = new LinkedHashSet<>();
        private final List<String> passwordProtected = new ArrayList<>();
        private final Set<String> skippedPostTypes = new LinkedHashSet<>();
        private final List<String> unparseableLinks = new ArrayList<>();

        @Override
        public void site(WxrSite site) {
            var home = Objects.requireNonNullElse(site.link(), "").replaceFirst("/+$", "");
            var homeUri = URI.create(home);
            var siteUri = URI.create(Objects.requireNonNullElse(site.baseSiteUrl(), home).replaceFirst("/+$", ""));
            homeScheme = Objects.requireNonNullElse(homeUri.getScheme(), "https");
            homeAuth = Objects.requireNonNullElse(homeUri.getRawAuthority(), "");
            siteAuth = Objects.requireNonNullElse(siteUri.getRawAuthority(), homeAuth);
            var sitePath = Objects.requireNonNullElse(siteUri.getRawPath(), "");
            // any scheme or none, optional www., home or site host, optional WP-in-subdirectory path
            var hostPath = "(?:www\\.)?(?:"
                    + Pattern.quote(noWww(homeAuth))
                    + "|"
                    + Pattern.quote(noWww(siteAuth))
                    + ")"
                    + (sitePath.isEmpty() ? "" : "(?:" + Pattern.quote(sitePath) + ")?")
                    + Pattern.quote(UPLOADS)
                    + URL_CHARS;
            // a Jetpack Photon URL (i0.wp.com/<home host>/...) is the same upload, its query only resizes
            uploadUrl = Pattern.compile(
                    "(?:https?:)?//(?:" + MediaRewriter.PHOTON + hostPath + MediaRewriter.PHOTON_QUERY + "|" + hostPath + ")"
            );
            settings.put("title", site.title());
            settings.put("description", site.description());
            settings.put("language", site.language());
            settings.put("home_url", home);
            settings.put("posts_per_page", String.valueOf(options.postsPerPage()));
            settings.put("posts_per_rss", String.valueOf(options.postsPerRss()));
            settings.put("category_base", options.categoryBase());
            settings.put("tag_base", options.tagBase());
        }

        @Override
        public void author(WxrAuthor a) {
            // an existing user with this login (the wizard's administrator) is the same person: keep role and password
            var r = authors.findByLogin(a.login()).orElseGet(() -> {
                var n = db.newRecord(AUTHOR);
                n.setLogin(a.login());
                n.setSlug(uniqueAuthorSlug(a));
                return n;
            });
            if (r.getDisplayName() == null) {
                r.setDisplayName(a.displayName());
            }
            if (r.getEmail() == null) {
                r.setEmail(a.email());
            }
            r.setSourceId(a.id());
            r.store();
            authorIds.put(a.login(), r.getId());
        }

        /**
         * {@code wp_insert_user}: nicename, then {@code -2}, {@code -3}... on collision.
         */
        private String uniqueAuthorSlug(WxrAuthor a) {
            var base = Nicename.of(a.login());
            if (base.isEmpty()) {
                base = "author-" + a.id();
            }
            var slug = base;
            for (int n = 2; !authorSlugs.add(slug); n++) {
                slug = base + "-" + n;
            }
            return slug;
        }

        @Override
        public void term(WxrTerm t) {
            var r = db.newRecord(TERM);
            r.setSourceId(t.id());
            r.setTaxonomy(t.taxonomy());
            r.setName(t.name());
            r.setSlug(Permalinks.decode(t.slug()));
            r.setDescription(t.description());
            r.store();
            if (t.parentSlug() != null && !t.parentSlug().isBlank()) {
                pendingTerms.add(new PendingTerm(r.getId(), t.taxonomy(), Permalinks.decode(t.parentSlug())));
            }
        }

        @Override
        public void item(WxrItem i) {
            switch (i.postType()) {
                case "post", "page", "attachment" -> importItem(i);
                case "wp_block" -> {
                    var r = db.newRecord(REUSABLE_BLOCK);
                    r.setSourceId(i.postId());
                    r.setTitle(i.title());
                    r.setAuthorId(authorIds.get(i.creator()));
                    r.setContent(localize(i.content()).rewrite(Objects.requireNonNullElse(i.content(), "")));
                    reportLeftovers(r.getContent());
                    // the editor needs a modified time for its concurrency check
                    r.setModifiedAt(
                            i.modifiedGmt() != null ? utc(i.modifiedGmt()) : LocalDateTime
                                .now(ZoneOffset.UTC)
                                .truncatedTo(ChronoUnit.MICROS)
                    );
                    r.store();
                    reusableCount++;
                }
                default -> skippedPostTypes.add(i.postType());
            }
        }

        private void importItem(WxrItem i) {
            var content = i.content();
            boolean blocks = content != null && content.contains("<!-- wp:");
            if (blocks) {
                collectUnknown(BlockParser.parse(content));
            } else if (content != null) {
                var m = SHORTCODE.matcher(content);
                while (m.find()) {
                    if (!KNOWN_SHORTCODES.contains(m.group(1))) {
                        unknownShortcodes.add(m.group(1));
                    }
                }
            }
            var attachment =
                    i.attachmentUrl() == null || i.attachmentUrl().isBlank() ? null : sourceKey(i.attachmentUrl());
            if (attachment != null && ensureMedia(attachment) == null && !skippedMedia.contains(attachment)) {
                missingMedia.add(attachment);
            }
            var rewriter = localize(content, i.excerpt());
            String permalink;
            try {
                permalink = permalinkOf(i.link());
            } catch (IllegalArgumentException e) {
                // one bad <link> must not abort a multi-hour import; the item stays, unpublished at any URL
                permalink = null;
                unparseableLinks.add(i.postId() + ": " + i.link());
            }

            var r = db.newRecord(ITEM);
            r.setSourceId(i.postId());
            r.setType(i.postType());
            r.setStatus(i.status());
            r.setPasswordProtected(i.password() != null && !i.password().isBlank());
            r.setTitle(i.title());
            r.setSlug(Permalinks.decode(i.postName()));
            r.setPermalink(permalink);
            r.setBody(rewriter.rewrite(content));
            r.setBodyFormat(blocks ? "blocks" : "classic");
            r.setExcerpt(rewriter.rewrite(i.excerpt()));
            reportLeftovers(r.getBody(), r.getExcerpt());
            r.setAuthorId(authorIds.get(i.creator()));
            r.setPublishedAt(utc(i.postDateGmt()));
            r.setPublishedLocal(i.postDate());
            r.setModifiedAt(utc(i.modifiedGmt()));
            r.setGuid(i.guid());
            r.setCommentStatus(i.commentStatus());
            r.setMenuOrder(i.menuOrder());
            if (i.attachmentUrl() != null) {
                r.setMimeType(URLConnection.guessContentTypeFromName(i.attachmentUrl()));
            }
            r.store();
            long id = r.getId();
            listener.item(++itemCount);

            if (attachment != null) {
                db.update(MEDIA).set(MEDIA.ITEM_ID, id).where(MEDIA.SOURCE_URL.eq(attachment)).execute();
            }
            linkTerms(id, i.terms());
            importComments(id, i);
            // redirects only lead to pages that will be published
            if (permalink != null
                    && !i.postType().equals("attachment")
                    && publishable(r.getStatus(), r.getPasswordProtected())) {
                redirect("?p=" + i.postId(), permalink);
                if (i.postType().equals("page")) {
                    redirect("?page_id=" + i.postId(), permalink);
                }
            }
            if (Boolean.TRUE.equals(r.getPasswordProtected())) {
                passwordProtected.add(permalink != null ? permalink : "?p=" + i.postId());
            }
            if (i.parentId() != 0) {
                pendingItems.add(new PendingItem(id, i.postId(), i.postType(), permalink, i.parentId()));
            }
        }

        private void collectUnknown(List<Block> blocks) {
            for (var b : blocks) {
                if (b.name() != null && !b.name().startsWith("core/") && !KNOWN_BLOCKS.contains(b.name())) {
                    unknownBlocks.add(b.name());
                }
                collectUnknown(b.innerBlocks());
            }
        }

        private void linkTerms(long itemId, List<WxrTermRef> refs) {
            var seen = new HashSet<Long>();
            for (var ref : refs) {
                var slug = Permalinks.decode(ref.nicename());
                var termId = db.fetchValue(TERM.ID, TERM.TAXONOMY.eq(ref.domain()).and(TERM.SLUG.eq(slug)));
                if (termId == null) {
                    // terms used inline but absent from the header (e.g. post_format) still get a row
                    var t = db.newRecord(TERM);
                    t.setTaxonomy(ref.domain());
                    t.setName(slug);
                    t.setSlug(slug);
                    t.store();
                    termId = t.getId();
                }
                if (seen.add(termId)) {
                    db.insertInto(ITEM_TERM, ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID).values(itemId, termId).execute();
                }
            }
        }

        private void importComments(long itemId, WxrItem i) {
            var ids = new HashMap<Long, Long>();
            for (var c : i.comments()) {
                var r = db.newRecord(COMMENT);
                r.setSourceId(c.id());
                r.setItemId(itemId);
                r.setAuthorName(c.author());
                r.setAuthorEmail(c.email());
                r.setAuthorUrl(c.url());
                r.setContent(Objects.requireNonNullElse(c.content(), ""));
                r.setCreatedAt(
                        utc(Objects.requireNonNullElse(
                                c.dateGmt(),
                                Objects.requireNonNullElse(i.postDateGmt(), Instant.EPOCH)
                        ))
                );
                r.setStatus(
                        switch (c.approved()) {
                            case "1" -> "approved";
                            case "0" -> "hold";
                            case null -> "hold";
                            default -> c.approved();
                        }
                );
                r.store();
                ids.put(c.id(), r.getId());
                commentCount++;
            }
            for (var c : i.comments()) {
                if (c.parentId() != 0 && ids.containsKey(c.parentId())) {
                    db.update(COMMENT).set(COMMENT.PARENT_ID, ids.get(c.parentId())).where(COMMENT.ID.eq(ids.get(c.id())
                    )).execute();
                }
            }
        }

        /**
         * Fetches every upload referenced by the texts and returns a rewriter for the ones now stored locally.
         */
        private MediaRewriter localize(String... texts) {
            var map = new HashMap<String, String>();
            if (uploadUrl != null) {
                for (var text : texts) {
                    if (text == null) {
                        continue;
                    }
                    var m = uploadUrl.matcher(text);
                    while (m.find()) {
                        // the Photon query goes; MediaRewriter matches it again when rewriting
                        var raw = m.group().replaceFirst("[.,;:!]+$", "").replaceFirst("\\?.*$", "");
                        var key = SIZE_VARIANT.matcher(raw).replaceFirst("");
                        var local = ensureMedia(sourceKey(key));
                        if (local == null && !key.equals(raw) && !skippedMedia.contains(sourceKey(key))) {
                            // a real original named like a size variant (screenshot-1920x1080.png)
                            key = raw;
                            local = ensureMedia(sourceKey(raw));
                        }
                        if (local != null) {
                            map.put(key, local);
                        }
                    }
                }
            }
            return new MediaRewriter(map);
        }

        /**
         * Every upload URL still in the text after rewriting is hotlinked; report it instead of leaving it silently.
         */
        private void reportLeftovers(String... texts) {
            for (var text : texts) {
                if (text != null) {
                    var m = ANY_UPLOAD.matcher(text);
                    while (m.find()) {
                        var url = m.group().replaceFirst("[.,;:!]+$", "");
                        if (!skippedMedia.contains(sourceKey(url))
                                && !skippedMedia.contains(sourceKey(SIZE_VARIANT.matcher(url).replaceFirst("")))) {
                            missingMedia.add(url);
                        }
                    }
                }
            }
        }

        /**
         * Dedupe and fetch key for an upload URL: this site's uploads get the home scheme and canonical host, so
         * http, https, protocol-relative and www. spellings share one MEDIA row. Other URLs are kept as written.
         */
        private String sourceKey(String url) {
            if (uploadUrl == null || !uploadUrl.matcher(url).matches()) {
                return url;
            }
            url = PHOTON_PREFIX.matcher(url.replaceFirst("\\?.*$", "")).replaceFirst("//");
            int start = url.indexOf("//") + 2;
            int slash = url.indexOf('/', start);
            var auth = noWww(url.substring(start, slash)).equals(noWww(homeAuth)) ? homeAuth : siteAuth;
            return homeScheme + "://" + auth + url.substring(slash);
        }

        /**
         * Local URL of an original upload, fetching it once; null when the source does not have it.
         */
        private String ensureMedia(String original) {
            var existing = media.findBySource(original);
            if (existing.isPresent()) {
                return existing.get().getPath() == null ? null : Permalinks.encode(existing.get().getPath());
            }
            if (mediaCount >= mediaLimit) {
                skippedMedia.add(original);
                return null;
            }
            URI uri;
            try {
                uri = URI.create(original);
            } catch (IllegalArgumentException e) {
                uri = null;
            }
            var bytes = uri == null ? Optional.<byte[]>empty() : fetcher.fetch(uri);
            var r = db.newRecord(MEDIA);
            r.setSourceUrl(original);
            if (bytes.isEmpty()) {
                r.store();
                return null;
            }
            var decoded = Permalinks.decodedPath(original);
            int at = decoded.indexOf(UPLOADS);
            var rel = at >= 0 ? decoded.substring(at + UPLOADS.length()) : decoded;
            var name = rel.substring(rel.lastIndexOf('/') + 1);
            var dm = DATED.matcher(rel);
            // shortcut: undated uploads (organize-by-month off) land in 0000/00; derive from the item date if that ever matters
            var month = dm.find() ? dm.group(1) + "/" + dm.group(2) : "0000/00";
            var sha = sha256(bytes.get());
            var path = "/media/" + month + "/" + sha.substring(0, 12) + "-" + name;
            write(month + "/" + sha.substring(0, 12) + "-" + name, bytes.get());
            r.setPath(path);
            r.setMimeType(URLConnection.guessContentTypeFromName(name));
            r.setSha256(sha);
            r.store();
            listener.media(++mediaCount);
            return Permalinks.encode(path);
        }

        private void write(String rel, byte[] bytes) {
            var root = dataDir.media();
            var file = root.resolve(rel).normalize();
            if (!file.startsWith(root) || file.getFileName().toString().isEmpty()) {
                throw new IllegalArgumentException("media path escapes media dir: " + rel);
            }
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void redirect(String from, String to) {
            db.insertInto(REDIRECT, REDIRECT.FROM_PATH, REDIRECT.TO_PATH, REDIRECT.STATUS_CODE).values(from, to, 301).execute();
        }

        /**
         * Parents may appear after their children in the stream, so they are linked once it ends.
         */
        void finish() {
            if (settings.get("permalink_structure").isEmpty()) {
                PermalinkStructure
                    .infer(importedPosts())
                    .ifPresent(v -> settings.put("permalink_structure", v));
            }
            for (var t : pendingTerms) {
                var parent = db.fetchValue(TERM.ID, TERM.TAXONOMY.eq(t.taxonomy()).and(TERM.SLUG.eq(t.parentSlug())));
                if (parent != null) {
                    db.update(TERM).set(TERM.PARENT_ID, parent).where(TERM.ID.eq(t.id())).execute();
                }
            }
            for (var p : pendingItems) {
                var parent = db
                    .select(ITEM.ID, ITEM.PERMALINK, ITEM.STATUS, ITEM.PASSWORD_PROTECTED)
                    .from(ITEM)
                    .where(ITEM.SOURCE_ID.eq(p.parentSourceId()))
                    .fetchOptional(Records.mapping(ParentRow::new))
                    .orElse(null);
                if (parent == null) {
                    continue;
                }
                db.update(ITEM).set(ITEM.PARENT_ID, parent.id()).where(ITEM.ID.eq(p.id())).execute();
                if (p.type().equals("attachment")
                        && parent.permalink() != null
                        && publishable(parent.status(), parent.passwordProtected())) {
                    if (p.permalink() != null) {
                        redirect(p.permalink(), parent.permalink());
                    }
                    redirect("?attachment_id=" + p.sourceId(), parent.permalink());
                }
            }
        }

        private record ParentRow(Long id, String permalink, String status, Boolean passwordProtected) {}

        private record TermRow(Long id, String slug, Long parentId) {}

        /**
         * The published posts with their category (the lowest id, as WordPress picks it) and author.
         */
        private List<PermalinkStructure.Fields> importedPosts() {
            var categories = db
                .select(TERM.ID, TERM.SLUG, TERM.PARENT_ID)
                .from(TERM)
                .where(TERM.TAXONOMY.eq("category"))
                .fetchMap(TERM.ID, Records.mapping(TermRow::new));
            var firstCategory = db
                .select(ITEM_TERM.ITEM_ID, DSL.min(ITEM_TERM.TERM_ID))
                .from(ITEM_TERM)
                .join(TERM)
                .on(TERM.ID.eq(ITEM_TERM.TERM_ID))
                .where(TERM.TAXONOMY.eq("category"))
                .groupBy(ITEM_TERM.ITEM_ID)
                .fetchMap(ITEM_TERM.ITEM_ID, DSL.min(ITEM_TERM.TERM_ID));
            return db
                .select(ITEM.ID, ITEM.SOURCE_ID, ITEM.SLUG, ITEM.PUBLISHED_LOCAL, AUTHOR.SLUG, ITEM.PERMALINK)
                .from(ITEM)
                .leftJoin(AUTHOR)
                .on(AUTHOR.ID.eq(ITEM.AUTHOR_ID))
                .where(ITEM.TYPE.eq("post"), ITEM.STATUS.eq("publish"), ITEM.PUBLISHED_LOCAL.isNotNull())
                .fetch(Records.mapping((id, sourceId, slug, publishedLocal, authorSlug, permalink) -> {
                    var path = new ArrayDeque<String>();
                    for (var t = categories.get(firstCategory.get(id)); t != null; t = categories.get(t.parentId())) {
                        path.addFirst(t.slug());
                    }
                    return new PermalinkStructure.Fields(
                            Objects.requireNonNullElse(sourceId, 0L),
                            Objects.requireNonNullElse(slug, ""),
                            publishedLocal,
                            String.join("/", path),
                            Objects.requireNonNullElse(authorSlug, ""),
                            permalink
                    );
                }));
        }

        ImportReport report() {
            return new ImportReport(
                    itemCount,
                    commentCount,
                    mediaCount,
                    reusableCount,
                    List.copyOf(unknownBlocks),
                    List.copyOf(unknownShortcodes),
                    List.copyOf(missingMedia),
                    List.copyOf(passwordProtected),
                    List.copyOf(skippedPostTypes),
                    List.copyOf(skippedMedia),
                    List.copyOf(unparseableLinks)
            );
        }
    }

    /**
     * Decoded path of the link; null for query-string links such as drafts ({@code /?p=101}).
     *
     * @throws IllegalArgumentException for a link that is not a URI, or whose path encodes {@code /} or {@code \}
     *         (decoding would change its segments, and the server refuses such paths)
     */
    static String permalinkOf(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        var uri = URI.create(link.strip());
        if (uri.getRawQuery() != null) {
            return null;
        }
        var raw = Objects.requireNonNullElse(uri.getRawPath(), "").toLowerCase(Locale.ROOT);
        if (raw.contains("%2f") || raw.contains("%5c")) {
            throw new IllegalArgumentException("encoded separator in " + link);
        }
        return Permalinks.decodedPath(link.strip());
    }

    /**
     * Private, draft, pending and password-protected items are never published.
     */
    static boolean publishable(String status, Boolean passwordProtected) {
        return "publish".equals(status) && !Boolean.TRUE.equals(passwordProtected);
    }

    private static String noWww(String authority) {
        return authority.startsWith("www.") ? authority.substring(4) : authority;
    }

    private static LocalDateTime utc(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
