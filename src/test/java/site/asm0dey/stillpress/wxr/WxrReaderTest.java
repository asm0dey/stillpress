package site.asm0dey.stillpress.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import module java.base;
import org.junit.jupiter.api.Test;

class WxrReaderTest {
  private static WxrDocument doc() {
    return WxrReader.read(WxrReaderTest.class.getClassLoader().getResourceAsStream("wxr/sample-blog.xml"));
  }
  private static WxrItem item(long id) {
    return doc().items().stream().filter(i -> i.postId() == id).findFirst().orElseThrow();
  }

  @Test void readsSiteAuthorsTermsAndItems() {
    var d = doc();
    assertThat(d.site().title()).isEqualTo("Блог");
    assertThat(d.site().language()).isEqualTo("ru-RU");
    assertThat(d.site().baseSiteUrl()).isEqualTo("https://blog.example.ru");
    assertThat(d.authors()).extracting(WxrAuthor::login).containsExactly("mama", "papa");
    assertThat(d.terms()).extracting(WxrTerm::slug).contains("travel", "europe", "kids");
    assertThat(d.terms()).filteredOn(t -> t.slug().equals("europe")).singleElement()
      .satisfies(t -> { assertThat(t.parentSlug()).isEqualTo("travel"); assertThat(t.taxonomy()).isEqualTo("category"); });
    var p = item(5994);
    assertThat(p.comments()).hasSize(3);
    assertThat(p.comments().get(1).parentId()).isEqualTo(1);
    assertThat(p.guid()).isEqualTo("https://blog.example.ru/?p=5994");
    assertThat(p.terms()).extracting(WxrTermRef::nicename).containsExactly("travel", "europe", "kids");
    assertThat(p.postDateGmt()).isEqualTo(Instant.parse("2023-01-14T10:00:00Z"));
    assertThat(d.items()).extracting(WxrItem::postType).doesNotContain("nav_menu_item");
    assertThat(item(101).postName()).isEqualTo("draft-post");
    assertThat(item(101).postDateGmt()).isNull();
  }
  @Test void titleEntitiesDecodedOnce() {
    assertThat(item(100).title()).isEqualTo("Q&A");
  }
  @Test void cdataSplitIsJoined() {
    assertThat(item(2).content()).contains("]]>");
  }
  @Test void passwordAndAttachmentUrlRead() {
    assertThat(item(102).password()).isEqualTo("secret");
    assertThat(item(7).attachmentUrl()).endsWith("/2020/05/c.jpg");
    assertThat(item(7).parentId()).isEqualTo(100);
  }
}
