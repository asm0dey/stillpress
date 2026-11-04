package site.asm0dey.stillpress.wxr;

import module java.base;

/**
 * Test helper: a whole export collected in memory through the streaming {@link WxrReader}.
 */
public record WxrDocument(WxrSite site, List<WxrAuthor> authors, List<WxrTerm> terms, List<WxrItem> items) {
    public static WxrDocument read(InputStream in) {
        var site = new WxrSite[1];
        var authors = new ArrayList<WxrAuthor>();
        var terms = new ArrayList<WxrTerm>();
        var items = new ArrayList<WxrItem>();
        WxrReader.read(in, new WxrHandler() {
            public void site(WxrSite s) {
                site[0] = s;
            }

            public void author(WxrAuthor a) {
                authors.add(a);
            }

            public void term(WxrTerm t) {
                terms.add(t);
            }

            public void item(WxrItem i) {
                items.add(i);
            }
        });
        return new WxrDocument(site[0], authors, terms, items);
    }
}
