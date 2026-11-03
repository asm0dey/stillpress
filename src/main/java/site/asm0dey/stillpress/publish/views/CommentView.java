package site.asm0dey.stillpress.publish.views;

import module java.base;

public record CommentView(
        long id,
        String authorName,
        String authorUrl,
        Instant createdAt,
        String contentHtml,
        List<CommentView> replies
) {
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
