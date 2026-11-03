package site.asm0dey.stillpress.wxr;

import module java.base;

public record WxrDocument(WxrSite site, List<WxrAuthor> authors, List<WxrTerm> terms, List<WxrItem> items) {}
