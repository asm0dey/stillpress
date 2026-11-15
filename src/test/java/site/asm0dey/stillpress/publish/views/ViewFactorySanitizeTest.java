package site.asm0dey.stillpress.publish.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.blocks.BlockRenderer;
import site.asm0dey.stillpress.db.tables.pojos.Comment;
import site.asm0dey.stillpress.db.tables.pojos.Item;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.CommentRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

class ViewFactorySanitizeTest {
    @Test
    void untrustedCommentHtmlAndAuthorUrlAreNeutralised() {
        var comments = mock(CommentRepository.class);
        var views = new ViewFactory(
                mock(BlockRenderer.class),
                mock(TermRepository.class),
                comments,
                mock(AuthorRepository.class),
                mock(SettingRepository.class),
                mock(ReusableBlockRepository.class)
        );
        var item = new Item(
                1L,
                null,
                "post",
                "publish",
                false,
                "T",
                "t",
                "/t/",
                null,
                "blocks",
                null,
                null,
                null,
                null,
                null,
                "g",
                "open",
                0,
                null,
                null
        );
        when(comments.approvedFor(1L))
            .thenReturn(List.of(
                    new Comment(
                            5L,
                            null,
                            1L,
                            null,
                            "Eve",
                            null,
                            "javascript:alert(1)",
                            "<script>alert(1)</script><a href=\"javascript:x\">k</a>ok",
                            LocalDateTime.now(),
                            "approved"
                    )
            ));
        var c = views.item(item).comments().getFirst();
        assertThat(c.authorName()).isEqualTo("Eve");
        assertThat(c.authorUrl()).isNull();
        assertThat(c.contentHtml()).doesNotContain("<script").doesNotContain("javascript:").contains("ok");
    }
}
