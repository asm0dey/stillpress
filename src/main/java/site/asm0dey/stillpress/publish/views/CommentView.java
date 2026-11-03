package site.asm0dey.stillpress.publish.views;

import module java.base;

public record CommentView(
        // WordPress comment_ID: the anchor and feed GUID suffix
        long id,
        String authorName,
        String authorUrl,
        Instant createdAt,
        String contentHtml,
        List<CommentView> replies
) {
    public CommentView {
        authorUrl = CommentSanitizer.safeUrl(authorUrl);
    }

    public String pubDate() {
        return ItemView.rfc1123(createdAt);
    }

    public String contentCdata() {
        return ItemView.cdata(contentHtml);
    }

    void flattenInto(List<CommentView> out) {
        out.add(this);
        replies.forEach(r -> r.flattenInto(out));
    }
}
