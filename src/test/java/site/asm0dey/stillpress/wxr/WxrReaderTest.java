package site.asm0dey.stillpress.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class WxrReaderTest {
    private static WxrDocument doc() {
        return WxrReader.read(WxrReaderTest.class.getClassLoader().getResourceAsStream("wxr/sample-blog.xml"));
    }

    private static WxrItem item(long id) {
        return doc()
            .items()
            .stream()
            .filter(i -> i.postId() == id)
            .findFirst()
            .orElseThrow();
    }

    @Test
    void readsSiteAuthorsTermsAndItems() {
        var d = doc();
        assertThat(d.site().title()).isEqualTo("Блог");
        assertThat(d.site().language()).isEqualTo("ru-RU");
        assertThat(d.site().baseSiteUrl()).isEqualTo("https://blog.example.ru");
        assertThat(d.authors()).extracting(WxrAuthor::login).containsExactly("mama", "papa");
        assertThat(d.terms()).extracting(WxrTerm::slug).contains("travel", "europe", "kids");
        assertThat(d.terms())
            .filteredOn(t -> t.slug().equals("europe"))
            .singleElement()
            .satisfies(t -> {
                assertThat(t.parentSlug()).isEqualTo("travel");
                assertThat(t.taxonomy()).isEqualTo("category");
            });
        var p = item(5994);
        assertThat(p.comments()).hasSize(3);
        assertThat(p.comments().get(1).parentId()).isEqualTo(1);
        assertThat(p.guid()).isEqualTo("https://blog.example.ru/?p=5994");
        assertThat(p.terms()).extracting(WxrTermRef::nicename).containsExactly("travel", "europe", "kids");
        assertThat(p.postDateGmt()).isEqualTo(Instant.parse("2023-01-14T10:00:00Z"));
        assertThat(d.items()).extracting(WxrItem::postType).contains("nav_menu_item", "wp_block", "attachment");
        assertThat(d.site().link()).isEqualTo("https://blog.example.ru");
        assertThat(item(101).postName()).isEqualTo("draft-post");
        assertThat(item(101).postDateGmt()).isNull();
    }

    @Test
    void streamsInDocumentOrder() {
        var events = new ArrayList<String>();
        WxrReader.read(
                WxrReaderTest.class.getClassLoader().getResourceAsStream("wxr/sample-blog.xml"),
                new WxrHandler() {
                    public void site(WxrSite s) {
                        events.add("site");
                    }

                    public void author(WxrAuthor a) {
                        events.add("author");
                    }

                    public void term(WxrTerm t) {
                        events.add("term");
                    }

                    public void item(WxrItem i) {
                        events.add("item");
                    }
                }
        );
        assertThat(events.getFirst()).isEqualTo("site");
        assertThat(events.indexOf("author")).isLessThan(events.indexOf("term"));
        assertThat(events.indexOf("term")).isLessThan(events.indexOf("item"));
        assertThat(events.stream().filter("item"::equals).count()).isEqualTo(8);
    }

    @Test
    void titleEntitiesDecodedOnce() {
        assertThat(item(100).title()).isEqualTo("Q&A");
    }

    @Test
    void rawCdataTitleNotDoubleDecoded() {
        var xml =
                "<rss xmlns:wp=\"http://wordpress.org/export/1.2/\"><channel><title><![CDATA[A &amp; B]]></title>"
                + "<item><title><![CDATA[Q&A]]></title><wp:post_id>1</wp:post_id><wp:post_type>post</wp:post_type></item>"
                + "<item><title><![CDATA[Q&amp;amp;A]]></title><wp:post_id>2</wp:post_id><wp:post_type>post</wp:post_type></item></channel></rss>";
        var d = WxrReader.read(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(d.site().title()).isEqualTo("A & B");
        assertThat(d.items()).extracting(WxrItem::title).containsExactly("Q&A", "Q&amp;A");
    }

    @Test
    void cdataSplitIsJoined() {
        assertThat(item(2).content()).contains("]]>");
    }

    @Test
    void passwordAndAttachmentUrlRead() {
        assertThat(item(102).password()).isEqualTo("secret");
        assertThat(item(7).attachmentUrl()).endsWith("/2020/05/c.jpg");
        assertThat(item(7).parentId()).isEqualTo(100);
    }
}
