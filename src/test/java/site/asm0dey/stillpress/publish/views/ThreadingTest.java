package site.asm0dey.stillpress.publish.views;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.db.tables.records.CommentRecord;

class ThreadingTest {
    private static CommentRecord c(long id, Long parent) {
        var r = new CommentRecord();
        r.setId(id);
        r.setParentId(parent);
        r.setContent("x");
        r.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        return r;
    }

    @Test
    void cyclesArePromotedNotDropped() {
        var out = ViewFactory.threaded(List.of(c(1, 1L), c(2, 3L), c(3, 2L), c(4, 3L), c(5, null), c(6, 5L)));
        assertThat(out).extracting(CommentView::id).containsExactlyInAnyOrder(1L, 2L, 3L, 5L);
        var three = out
            .stream()
            .filter(v -> v.id() == 3)
            .findFirst()
            .orElseThrow();
        assertThat(three.replies()).extracting(CommentView::id).containsExactly(4L);
        var five = out
            .stream()
            .filter(v -> v.id() == 5)
            .findFirst()
            .orElseThrow();
        assertThat(five.replies()).extracting(CommentView::id).containsExactly(6L);
    }
}
