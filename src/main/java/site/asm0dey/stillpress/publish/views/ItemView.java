package site.asm0dey.stillpress.publish.views;

import module java.base;

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
     * RFC 1123 in GMT with {@code +0000}, as WordPress writes pubDate.
     */
    public String pubDate() {
        return rfc1123(publishedAt);
    }

    public static String rfc1123(Instant t) {
        return t == null
                ? ""
                : DateTimeFormatter.RFC_1123_DATE_TIME.format(t.atOffset(ZoneOffset.UTC)).replace("GMT", "+0000");
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
}
