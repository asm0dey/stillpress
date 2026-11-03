package site.asm0dey.stillpress.wxr;

import module java.base;

public record WxrItem(
        long postId,
        String postType,
        String status,
        String title,
        String link,
        String guid,
        String postName,
        String content,
        String excerpt,
        Instant postDateGmt,
        Instant modifiedGmt,
        String creator,
        long parentId,
        int menuOrder,
        String password,
        String commentStatus,
        String attachmentUrl,
        List<WxrTermRef> terms,
        Map<String, String> meta,
        List<WxrComment> comments
) {}
