package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class NicenameTest {
    @Test
    void matchesWordPressUserNicename() {
        assertThat(Nicename.of("Pavel.Doe")).isEqualTo("pavel-doe");
        assertThat(Nicename.of("john@doe.com")).isEqualTo("johndoe-com");
        assertThat(Nicename.of("  Ann  Smith ")).isEqualTo("ann-smith");
        assertThat(Nicename.of("José_Ñ")).isEqualTo("jose_n");
        assertThat(Nicename.of("mama")).isEqualTo("mama");
    }
}
