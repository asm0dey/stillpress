package site.asm0dey.stillpress.wxr;

import module java.base;
import module java.xml;
import org.jsoup.parser.Parser;

/**
 * Streaming StAX reader for WXR 1.x exports.
 */
public final class WxrReader {
    private WxrReader() {
    }

    private static final DateTimeFormatter GMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Collects everything in memory; for tests and small files. Use {@link #read(InputStream, WxrHandler)} for real exports.
     */
    public static WxrDocument read(InputStream in) {
        var site = new WxrSite[1];
        var authors = new ArrayList<WxrAuthor>();
        var terms = new ArrayList<WxrTerm>();
        var items = new ArrayList<WxrItem>();
        read(in, new WxrHandler() {
            public void site(WxrSite s) {
                site[0] = s;
            }

            public void author(WxrAuthor a) {
                authors.add(a);
            }

            public void term(WxrTerm t) {
                terms.add(t);
            }

            public void item(WxrItem i) {
                items.add(i);
            }
        });
        return new WxrDocument(site[0], authors, terms, items);
    }

    /**
     * Streams the export: only the current item is held in memory. Every post type is passed through.
     */
    public static void read(InputStream in, WxrHandler h) {
        XMLStreamReader r = null;
        try {
            var f = safeFactory();
            f.setProperty(XMLInputFactory.IS_COALESCING, true);
            r = f.createXMLStreamReader(in);
            var ch = new HashMap<String, String>();
            boolean siteSent = false;
            int depth = 0, channelDepth = -1;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    continue;
                }
                if (ev != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                var name = r.getLocalName();
                // Only direct children of <channel>: <image><title> from rss2_head must not overwrite the site title.
                if (channelDepth < 0 || depth != channelDepth) {
                    depth++;
                    if (channelDepth < 0 && name.equals("channel")) {
                        channelDepth = depth;
                    }
                    continue;
                }
                boolean header = switch (name) {
                    case "title", "link", "description", "language", "base_site_url", "base_blog_url" -> {
                        ch.put(name, text(r));
                        yield true;
                    }
                    default -> false;
                };
                if (header) {
                    continue;
                }
                if (!siteSent
                        && switch (name) {
                    case "author", "category", "tag", "term", "item" -> true;
                    default -> false;
                }) {
                    h.site(site(ch));
                    siteSent = true;
                }
                switch (name) {
                    case "author" -> {
                        var m = flat(r);
                        h.author(
                                new WxrAuthor(
                                        num(m.get("author_id")),
                                        m.get("author_login"),
                                        m.get("author_email"),
                                        m.get("author_display_name")
                                )
                        );
                    }
                    case "category" -> {
                        var m = flat(r);
                        h.term(
                                new WxrTerm(
                                        num(m.get("term_id")),
                                        "category",
                                        m.get("category_nicename"),
                                        unescape(m.get("cat_name")),
                                        blankToNull(m.get("category_parent")),
                                        m.get("category_description")
                                )
                        );
                    }
                    case "tag" -> {
                        var m = flat(r);
                        h.term(
                                new WxrTerm(
                                        num(m.get("term_id")),
                                        "post_tag",
                                        m.get("tag_slug"),
                                        unescape(m.get("tag_name")),
                                        null,
                                        m.get("tag_description")
                                )
                        );
                    }
                    case "term" -> {
                        var m = flat(r);
                        h.term(
                                new WxrTerm(
                                        num(m.get("term_id")),
                                        m.get("term_taxonomy"),
                                        m.get("term_slug"),
                                        unescape(m.get("term_name")),
                                        blankToNull(m.get("term_parent")),
                                        m.get("term_description")
                                )
                        );
                    }
                    case "item" -> h.item(item(r));
                    default -> depth++;
                }
            }
            if (!siteSent) {
                h.site(site(ch));
            }
        } catch (XMLStreamException e) {
            throw new UncheckedIOException(new IOException("Invalid WXR: " + e.getMessage(), e));
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException e) {
                    /* nothing to recover */
                }
            }
        }
    }

    /**
     * No DTDs and no external entities. With those off, only the five predefined entities and character references
     * remain, so the JDK's entity-size limits (100,000 references by default) only break large exports and are lifted.
     */
    public static XMLInputFactory safeFactory() {
        var f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty("jdk.xml.maxGeneralEntitySizeLimit", 0);
        f.setProperty("jdk.xml.totalEntitySizeLimit", 0);
        f.setProperty("jdk.xml.entityExpansionLimit", 0);
        return f;
    }

    private static WxrSite site(Map<String, String> ch) {
        return new WxrSite(
                unescape(ch.get("title")),
                ch.get("link"),
                unescape(ch.get("description")),
                ch.get("language"),
                ch.get("base_site_url"),
                ch.get("base_blog_url")
        );
    }

    private static WxrItem item(XMLStreamReader r) throws XMLStreamException {
        var m = new HashMap<String, String>();
        var terms = new ArrayList<WxrTermRef>();
        var meta = new LinkedHashMap<String, String>();
        var comments = new ArrayList<WxrComment>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("item")) {
                break;
            }
            if (ev != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "category" -> {
                    var d = r.getAttributeValue(null, "domain");
                    var n = r.getAttributeValue(null, "nicename");
                    text(r);
                    terms.add(new WxrTermRef(d, n));
                }
                case "postmeta" -> {
                    var p = flat(r);
                    meta.put(p.get("meta_key"), p.get("meta_value"));
                }
                case "comment" -> {
                    var c = flat(r);
                    comments.add(
                            new WxrComment(
                                    num(c.get("comment_id")),
                                    num(c.get("comment_parent")),
                                    c.get("comment_author"),
                                    c.get("comment_author_email"),
                                    c.get("comment_author_url"),
                                    instant(c.get("comment_date_gmt")),
                                    c.get("comment_content"),
                                    c.get("comment_approved")
                            )
                    );
                }
                case "encoded" -> m.put(r.getNamespaceURI().endsWith("/content/") ? "content" : "excerpt", text(r));
                default -> m.put(r.getLocalName(), text(r));
            }
        }
        return new WxrItem(
                num(m.get("post_id")),
                m.get("post_type"),
                m.get("status"),
                unescape(m.get("title")),
                m.get("link"),
                m.get("guid"),
                m.get("post_name"),
                m.get("content"),
                m.get("excerpt"),
                instant(m.get("post_date_gmt")),
                instant(m.get("post_modified_gmt")),
                m.get("creator"),
                num(m.get("post_parent")),
                (int) num(m.get("menu_order")),
                blankToNull(m.get("post_password")),
                m.get("comment_status"),
                m.get("attachment_url"),
                terms,
                meta,
                comments
        );
    }

    /**
     * Reads the children of the current element into a name to text map; consumes through its end tag.
     */
    private static Map<String, String> flat(XMLStreamReader r) throws XMLStreamException {
        var m = new HashMap<String, String>();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
                m.put(r.getLocalName(), text(r));
                depth--;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return m;
    }

    /**
     * Text of the current element at any depth; consumes through its end tag.
     */
    private static String text(XMLStreamReader r) throws XMLStreamException {
        var sb = new StringBuilder();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            switch (r.next()) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> sb.append(r.getText());
                default -> {
                }
            }
        }
        return sb.toString();
    }

    // WP stores titles raw or entity-escaped depending on unfiltered_html; XML parsing already ran once, so this is the single HTML decode.
    private static String unescape(String s) {
        return s == null ? null : Parser.unescapeEntities(s, false);
    }

    private static long num(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank() || s.startsWith("0000")) {
            return null;
        }
        try {
            return GMT.parse(s.trim(), Instant::from);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
