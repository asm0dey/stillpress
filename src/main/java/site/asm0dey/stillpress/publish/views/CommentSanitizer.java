package site.asm0dey.stillpress.publish.views;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Comments are untrusted: reduce them to WordPress's allowed comment tags plus p/br.
 */
public final class CommentSanitizer {
    private static final Safelist ALLOWED = Safelist
        .none()
        .addTags(
                "a",
                "abbr",
                "acronym",
                "b",
                "blockquote",
                "cite",
                "code",
                "del",
                "em",
                "i",
                "q",
                "s",
                "strike",
                "strong",
                "p",
                "br"
        )
        .addAttributes("a", "href", "title")
        .addAttributes("abbr", "title")
        .addAttributes("acronym", "title")
        .addAttributes("blockquote", "cite")
        .addAttributes("del", "datetime")
        .addAttributes("q", "cite")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("blockquote", "cite", "http", "https")
        .addProtocols("q", "cite", "http", "https")
        .addEnforcedAttribute("a", "rel", "nofollow ugc");

    private CommentSanitizer() {
    }

    public static String clean(String html) {
        var out = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html == null ? "" : html, "", ALLOWED, out);
    }

    /**
     * Only absolute http(s) URLs are linkable; anything else is null.
     */
    public static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        var u = url.strip();
        return u.regionMatches(true, 0, "http://", 0, 7) || u.regionMatches(true, 0, "https://", 0, 8) ? u : null;
    }
}
