package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;

@QuarkusTest
class AuthorRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void createsUniqueSlugsAndFindsByLoginOrEmail() {
        var a = users.create("John.Doe", "john@example.org", "John", Role.EDITOR);
        var b = users.create("john doe", "other@example.org", "John 2", Role.AUTHOR);
        assertThat(a.getSlug()).isEqualTo("john-doe");
        assertThat(b.getSlug()).isEqualTo("john-doe-2");
        assertThat(Role.of(a.getRole())).isEqualTo(Role.EDITOR);
        assertThat(users.findByLoginOrEmail("John.Doe"))
            .map(r -> r.getId())
            .contains(a.getId());
        assertThat(users.findByLoginOrEmail("JOHN@example.org"))
            .map(r -> r.getId())
            .contains(a.getId());
        assertThat(users.findByLoginOrEmail("john.doe")).isEmpty();
    }

    @Test
    void ambiguousEmailMatchesNobody() {
        users.create("one", "same@example.org", "One", Role.AUTHOR);
        users.create("two", "Same@example.org", "Two", Role.AUTHOR);
        assertThat(users.findByLoginOrEmail("same@example.org")).isEmpty();
    }

    @Test
    void administratorsWithPassword() {
        var a = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        assertThat(users.countAdministrators()).isEqualTo(1);
        assertThat(users.anyAdministratorWithPassword()).isFalse();
        users.setPassword(a.getId(), Passwords.hash("long enough password"));
        assertThat(users.anyAdministratorWithPassword()).isTrue();
        users.setRole(a.getId(), Role.EDITOR);
        assertThat(users.countAdministrators()).isZero();
        assertThat(users.anyAdministratorWithPassword()).isFalse();
    }
}
