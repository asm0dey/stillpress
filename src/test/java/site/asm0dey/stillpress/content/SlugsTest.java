package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SlugsTest {
    @Test
    void cyrillicKeptLowerCased() {
        assertThat(Slugs.title("Месяц Ноябрь, год 2022")).isEqualTo("месяц-ноябрь-год-2022");
    }

    @Test
    void cyrillicBreveSurvivesAccentRemoval() {
        assertThat(Slugs.title("Район")).isEqualTo("район");
        assertThat(Slugs.title("Й")).isEqualTo("й");
    }

    @Test
    void punctuationAndSpaces() {
        assertThat(Slugs.title("  Hello, World!  ")).isEqualTo("hello-world");
    }

    @Test
    void accentsRemoved() {
        assertThat(Slugs.title("Café au lait")).isEqualTo("cafe-au-lait");
    }

    @Test
    void tagsStripped() {
        assertThat(Slugs.title("<b>Bold</b> move")).isEqualTo("bold-move");
    }

    @Test
    void entitiesDropped() {
        assertThat(Slugs.title("Fish &amp; chips")).isEqualTo("fish-chips");
    }

    @Test
    void dotsAndDashRuns() {
        assertThat(Slugs.title("v1.2 -- final")).isEqualTo("v1-2-final");
    }

    @Test
    void nothingLeft() {
        assertThat(Slugs.title("!!!")).isEmpty();
    }
}
