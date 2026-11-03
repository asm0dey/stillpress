package site.asm0dey.stillpress.blocks;

import module java.base;

/**
 * Classic (non-block) post content: [caption] shortcode plus a port of WordPress wpautop(). Other shortcodes stay verbatim.
 */
public final class ClassicContent {
    private ClassicContent() {
    }

    private static final String ALL_BLOCKS =
            "(?:"
            + "table|thead|tfoot|caption|col|colgroup|tbody|tr|td|th|div|dl|dd|dt|ul|ol|li|pre|form|map|a"
            + "rea|blockquote|address|style|p|h[1-6]"
            + "|hr|fieldset|legend|section|article|aside|hgroup|header|footer|nav|figure|figcaption|detai"
            + "ls|menu|summary)";
    private static final Pattern CAPTION =
            Pattern.compile("\\[caption((?:\\s[^\\]]*)?)\\](.*?)\\[/caption\\]", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^<>]*>");
    private static final Pattern PRESERVE = Pattern.compile("<(script|style|svg|math).*?</\\1>", Pattern.DOTALL);

    // the_content order: wpautop (10), shortcode_unautop, then [caption] (11).
    public static String toHtml(String classicHtml) {
        return captions(UNAUTOP.matcher(autop(classicHtml)).replaceAll("$1"));
    }

    // shortcode_unautop() from wp-includes/formatting.php, with the caption tag only.
    private static final String SP = "(?:[\\r\\n\\t ]|&nbsp;)";
    private static final Pattern UNAUTOP = Pattern.compile(
            "<p>"
            + SP
            + "*+(\\[(caption)(?![\\w-])[^\\]/]*(?:/(?!\\])[^\\]/]*)*?(?:/\\]|\\](?:[^\\[]*+(?:\\[(?!/\\2\\])[^\\[]*+)*+\\[/\\2\\])?))"
            + SP
            + "*+</p>"
    );
    private static final Pattern IMG_SPLIT = Pattern.compile(
            "((?:<a [^>]+>\\s*)?<img [^>]+>(?:\\s*</a>)?)(.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ATTR = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|'([^']*)')");

    private static String htmlClass(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "");
    }

    static String attr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Port of img_caption_shortcode() (HTML5 branch) from wp-includes/media.php.
     */
    private static String captions(String s) {
        return CAPTION
            .matcher(s)
            .replaceAll(r -> {
                var attrs = new HashMap<String, String>();
                var am = ATTR.matcher(r.group(1));
                while (am.find()) {
                    attrs.put(am.group(1), am.group(2) != null ? am.group(2) : am.group(3));
                }
                var body = r.group(2);
                var media = body;
                var text = "";
                if (!attrs.containsKey("caption")) {
                    var im = IMG_SPLIT.matcher(body);
                    if (im.find()) {
                        media = im.group(1);
                        text = im.group(2).trim();
                    }
                } else {
                    // shortcut: WP runs wp_kses(post) on a caption attribute containing '<'; kept verbatim here since the content is the author's own export.
                    text = attrs.get("caption");
                }
                int width = 0;
                try {
                    width = Integer.parseInt(attrs.getOrDefault("width", "").trim());
                } catch (NumberFormatException e) {
                    /* width 0 */
                }
                if (width < 1 || text.isEmpty()) {
                    return Matcher.quoteReplacement(media);
                }
                var id = htmlClass(attrs.getOrDefault("id", ""));
                var captionId = htmlClass(attrs.getOrDefault("caption_id", ""));
                // shortcut: WP's wp_unique_prefixed_id suffixes repeated ids (-2, -3); not tracked per page, add a per-document counter if duplicate caption ids occur
                if (captionId.isEmpty() && !id.isEmpty()) {
                    captionId = "caption-" + id.replace('_', '-');
                }
                var cls = (("wp-caption "
                        + attrs.getOrDefault("align", "alignnone")
                        + " "
                        + attrs.getOrDefault("class", ""))
                    .trim())
                    .replaceAll("\\s+", " ");
                return Matcher.quoteReplacement(
                        "<figure "
                        + (id.isEmpty() ? "" : "id=\"" + attr(id) + "\" ")
                        + (captionId.isEmpty() ? "" : "aria-describedby=\"" + attr(captionId) + "\" ")
                        + "style=\"width: "
                        + width
                        + "px\" class=\""
                        + attr(cls)
                        + "\">"
                        + media
                        + "<figcaption "
                        + (captionId.isEmpty() ? "" : "id=\"" + attr(captionId) + "\" ")
                        + "class=\"wp-caption-text\">"
                        + text
                        + "</figcaption></figure>"
                );
            });
    }

    /**
     * wpautop($text, br=true).
     */
    static String autop(String text) {
        if (text.trim().isEmpty()) {
            return "";
        }
        text += "\n";
        var pre = new LinkedHashMap<String, String>();
        if (text.contains("<pre")) {
            var parts = text.split("</pre>", -1);
            var sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                int start = parts[i].indexOf("<pre");
                if (start < 0) {
                    sb.append(parts[i]);
                    continue;
                }
                var name = "<pre wp-pre-tag-" + pre.size() + "></pre>";
                pre.put(name, parts[i].substring(start) + "</pre>");
                sb.append(parts[i], 0, start).append(name);
            }
            text = sb.append(parts[parts.length - 1]).toString();
        }
        text = text.replaceAll("<br\\s*/?>\\s*<br\\s*/?>", "\n\n");
        text = text.replaceAll("(<" + ALL_BLOCKS + "[\\s/>])", "\n\n$1");
        text = text.replaceAll("(</" + ALL_BLOCKS + ">)", "$1\n\n");
        text = text.replaceAll("(<hr\\s*?/?>)", "$1\n\n");
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        // shortcut: wp_replace_in_html_tags simplified to a <[^<>]*> regex (no special-casing of HTML comments or > inside attributes); port the real tokenizer if such content mis-wraps.
        // shortcut: wpautop's <option> and <object> whitespace special-cases are not ported; port them if select/object markup appears in classic content.
        // newlines inside tags must survive paragraph splitting
        text = TAG
            .matcher(text)
            .replaceAll(m -> Matcher.quoteReplacement(m.group().replace("\n", " <!-- wpnl --> ")));
        if (text.contains("<source") || text.contains("<track")) {
            text = text.replaceAll("([<\\[](?:audio|video)[^>\\]]*[>\\]])\\s*", "$1");
            text = text.replaceAll("\\s*([<\\[]/(?:audio|video)[>\\]])", "$1");
            text = text.replaceAll("\\s*(<(?:source|track)[^>]*>)\\s*", "$1");
        }
        if (text.contains("<figcaption")) {
            text = text.replaceAll("\\s*(<figcaption[^>]*>)", "$1");
            text = text.replaceAll("</figcaption>\\s*", "</figcaption>");
        }
        text = text.replaceAll("\n\n+", "\n\n");
        var sb = new StringBuilder();
        for (var t : text.split("\n\\s*\n")) {
            if (t.isEmpty()) {
                continue;
            }
            sb.append("<p>").append(t.replaceAll("^\n+|\n+$", "")).append("</p>\n");
        }
        text = sb.toString();
        text = text.replaceAll("<p>\\s*</p>", "");
        text = text.replaceAll("<p>([^<]+)</(div|address|form)>", "<p>$1</p></$2>");
        text = text.replaceAll("<p>\\s*(</?" + ALL_BLOCKS + "[^>]*>)\\s*</p>", "$1");
        text = text.replaceAll("<p>(<li.+?)</p>", "$1");
        text = text.replaceAll("(?i)<p><blockquote([^>]*)>", "<blockquote$1><p>");
        text = text.replace("</blockquote></p>", "</p></blockquote>");
        text = text.replaceAll("<p>\\s*(</?" + ALL_BLOCKS + "[^>]*>)", "$1");
        text = text.replaceAll("(</?" + ALL_BLOCKS + "[^>]*>)\\s*</p>", "$1");
        text = PRESERVE
            .matcher(text)
            .replaceAll(m -> Matcher.quoteReplacement(m.group().replace("\n", "<WPPreserveNewline />")));
        text = text.replace("<br>", "<br />").replace("<br/>", "<br />");
        text = text.replaceAll("(?<!<br />)\\s*\n", "<br />\n");
        text = text.replace("<WPPreserveNewline />", "\n");
        text = text.replaceAll("(</?" + ALL_BLOCKS + "[^>]*>)\\s*<br />", "$1");
        text = text.replaceAll("<br />(\\s*</?(?:p|li|div|dl|dd|dt|th|pre|td|ul|ol)[^>]*>)", "$1");
        text = text.replaceAll("\n</p>$", "</p>");
        for (var e : pre.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text.replace(" <!-- wpnl --> ", "\n").replace("<!-- wpnl -->", "\n");
    }
}
