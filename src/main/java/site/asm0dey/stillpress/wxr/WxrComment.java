package site.asm0dey.stillpress.wxr;

import module java.base;

public record WxrComment(long id, long parentId, String author, String email, String url, Instant dateGmt, String content, String approved) {}
