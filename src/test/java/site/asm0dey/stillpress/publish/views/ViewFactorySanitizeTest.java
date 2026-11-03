package site.asm0dey.stillpress.publish.views;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.COMMENT;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.ItemRepository;

@QuarkusTest
class ViewFactorySanitizeTest {
    @Inject
    TestStore store;
    @Inject
    ViewFactory views;
    @Inject
    ItemRepository items;
    @Inject
    DSLContext db;

    @Test
    void untrustedCommentHtmlAndAuthorUrlAreNeutralised() throws Exception {
        store.reset();
        store.importSample();
        var item = items.findPublished().getFirst();
        db
            .insertInto(
                    COMMENT,
                    COMMENT.ITEM_ID,
                    COMMENT.AUTHOR_NAME,
                    COMMENT.AUTHOR_URL,
                    COMMENT.CONTENT,
                    COMMENT.CREATED_AT,
                    COMMENT.STATUS
            )
            .values(
                    item.getId(),
                    "Eve",
                    "javascript:alert(1)",
                    "<script>alert(1)</script><a href=\"javascript:x\">k</a>ok",
                    LocalDateTime.now(),
                    "approved"
            )
            .execute();
        var v = views.item(item);
        var c = v
            .comments()
            .stream()
            .filter(x -> x.authorName().equals("Eve"))
            .findFirst()
            .orElseThrow();
        assertThat(c.authorUrl()).isNull();
        assertThat(c.contentHtml()).doesNotContain("<script").doesNotContain("javascript:").contains("ok");
    }
}
