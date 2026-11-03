package site.asm0dey.stillpress.wxr;

/** Receives WXR content in document order: site first, then authors, terms and items; one item is alive at a time. */
public interface WxrHandler {
  void site(WxrSite site);
  void author(WxrAuthor author);
  void term(WxrTerm term);
  void item(WxrItem item);
}
