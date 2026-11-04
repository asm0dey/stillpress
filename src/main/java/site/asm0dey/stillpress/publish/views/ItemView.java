package site.asm0dey.stillpress.publish.views;

import module java.base;
import org.jsoup.Jsoup;

public record ItemView(
        long id,
        String title,
        String permalinkEncoded,
        String absoluteUrl,
        String contentHtml,
        String excerptHtml,
        Instant publishedAt,
        String authorName,
        String authorUrl,
        List<TermLink> categories,
        List<TermLink> tags,
        List<CommentView> comments,
        String guid
) {
    private static final DateTimeFormatter RSS_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.ENGLISH);

    public ItemView withGuid(String newGuid) {
        return new ItemView(
                id,
                title,
                permalinkEncoded,
                absoluteUrl,
                contentHtml,
                excerptHtml,
                publishedAt,
                authorName,
                authorUrl,
                categories,
                tags,
                comments,
                newGuid
        );
    }

    /**
     * WordPress's {@code D, d M Y H:i:s +0000}.
     */
    public String pubDate() {
        return rfc1123(publishedAt);
    }

    public static String rfc1123(Instant t) {
        return t == null ? "" : RSS_DATE.format(t.atOffset(ZoneOffset.UTC));
    }

    /**
     * For {@code <![CDATA[...]]>}: a literal {@code ]]>} is split across two sections.
     */
    public static String cdata(String s) {
        return s.replace("]]>", "]]]]><![CDATA[>");
    }

    public String contentCdata() {
        return cdata(contentHtml);
    }

    /**
     * Every comment, depth first, for the comments feed.
     */
    public List<CommentView> allComments() {
        var out = new ArrayList<CommentView>();
        comments.forEach(c -> c.flattenInto(out));
        return out;
    }

    public String feedExcerptCdata() {
        return cdata(feedExcerpt());
    }

    /**
     * WordPress's the_excerpt_rss: the excerpt, else the content stripped to 55 words plus {@code [&hellip;]}.
     */
    public String feedExcerpt() {
        if (excerptHtml != null && !excerptHtml.isBlank()) {
            return excerptHtml;
        }
        return trimmed(contentHtml, " [&hellip;]");
    }

    /**
     * WordPress's {@code wp_trim_words}: the first 55 words of {@code html} with tags stripped and whitespace
     * collapsed, escaped for HTML, followed by {@code more} when the text was cut.
     */
    static String trimmed(String html, String more) {
        var words = Arrays
            .stream(Jsoup.parse(html == null ? "" : html).text().split("[\\s\\p{Z}]+"))
            .filter(w -> !w.isEmpty())
            .toList();
        var text = String
            .join(" ", words.subList(0, Math.min(words.size(), 55)))
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
        return words.size() > 55 ? text + more : text;
    }

    /**
     * Date of the newest comment, else of the post, for the comments feed.
     */
    public String lastCommentDate() {
        return rfc1123(allComments()
            .stream()
            .map(CommentView::createdAt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(publishedAt)
        );
    }
}
