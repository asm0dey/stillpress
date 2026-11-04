package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.content.PermalinkStructure.Fields;

class PermalinkStructureTest {
    private final Fields f =
            new Fields(42, "месяц", LocalDateTime.parse("2023-01-14T01:30:00"), "travel/europe", "mama", null);

    @Test
    void rendersDayAndName() {
        assertThat(PermalinkStructure.render(PermalinkStructure.DEFAULT, f)).isEqualTo("/2023/01/14/месяц/");
    }

    @Test
    void rendersCategoryAndId() {
        assertThat(PermalinkStructure.render("/%category%/%postname%/", f)).isEqualTo("/travel/europe/месяц/");
        assertThat(PermalinkStructure.render("/archives/%post_id%/", f)).isEqualTo("/archives/42/");
    }

    @Test
    void infersFromImportedPosts() {
        var posts = List.of(
                new Fields(1, "a", LocalDateTime.parse("2020-05-02T10:00:00"), "x", "m", "/2020/05/a/"),
                new Fields(2, "b", LocalDateTime.parse("2021-06-03T10:00:00"), "x", "m", "/2021/06/b/")
        );
        assertThat(PermalinkStructure.infer(posts)).contains("/%year%/%monthnum%/%postname%/");
    }

    @Test
    void noMatchInfersNothing() {
        var posts = List.of(
                new Fields(1, "a", LocalDateTime.parse("2020-05-02T10:00:00"), "x", "m", "/weird/1/"),
                new Fields(2, "b", LocalDateTime.parse("2021-06-03T10:00:00"), "x", "m", "/odd/2/")
        );
        assertThat(PermalinkStructure.infer(posts)).isEmpty();
        assertThat(PermalinkStructure.infer(List.of())).isEmpty();
    }
}
