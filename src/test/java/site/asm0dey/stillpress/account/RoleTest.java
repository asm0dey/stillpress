package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class RoleTest {
    @Test
    void rolesRoundTrip() {
        assertThat(Role.ADMINISTRATOR.id()).isEqualTo("administrator");
        assertThat(Role.of("contributor")).isEqualTo(Role.CONTRIBUTOR);
        assertThatThrownBy(() -> Role.of("subscriber")).isInstanceOf(IllegalArgumentException.class);
    }
}
