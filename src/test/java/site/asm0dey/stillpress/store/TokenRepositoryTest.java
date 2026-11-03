package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;

@QuarkusTest
class TokenRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    TokenRepository tokens;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void tokensAreSingleUseAndPurposeBound() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raw = tokens.create(u.getId(), "set_password", Duration.ofHours(24));
        assertThat(tokens.consume(raw, "other")).isEmpty();
        assertThat(tokens.consume(raw, "set_password")).contains(u.getId());
        assertThat(tokens.consume(raw, "set_password")).isEmpty();
        var expired = tokens.create(u.getId(), "set_password", Duration.ofSeconds(-1));
        assertThat(tokens.consume(expired, "set_password")).isEmpty();
        assertThat(tokens.consume("unknown", "set_password")).isEmpty();
    }
}
