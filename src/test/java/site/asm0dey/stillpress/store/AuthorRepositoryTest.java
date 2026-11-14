package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;

@QuarkusTest
class AuthorRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    DSLContext db;

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
        // duplicates left over from before the unique key keep a NULL email_lower
        legacy("one", "same@example.org");
        legacy("two", "Same@example.org");
        assertThat(users.findByLoginOrEmail("same@example.org")).isEmpty();
    }

    @Test
    void backfillSkipsExistingDuplicates() throws Exception {
        legacy("one", "Dup@example.org");
        legacy("two", "dup@example.org");
        legacy("three", "Solo@example.org");
        legacy("four", null);
        // the migration is the single source of the backfill statement
        var sql = Files
            .readAllLines(Path.of("src/main/resources/db/migration/common/V8__email_lower.sql"))
            .stream()
            .filter(l -> l.startsWith("UPDATE author"))
            .findFirst()
            .orElseThrow();
        db.execute(sql);
        assertThat(db
            .select(AUTHOR.LOGIN, AUTHOR.EMAIL_LOWER)
            .from(AUTHOR)
            .orderBy(AUTHOR.LOGIN)
            .fetchMap(AUTHOR.LOGIN, AUTHOR.EMAIL_LOWER)
        )
            .containsEntry("one", null)
            .containsEntry("two", null)
            .containsEntry("three", "solo@example.org")
            .containsEntry("four", null);
    }

    @Test
    void emailLowerTracksCreateAndProfileChange() {
        var a = users.create("five", "Mixed@Example.org", "Five", Role.AUTHOR);
        assertThat(users.findById(a.getId()).orElseThrow().getEmailLower()).isEqualTo("mixed@example.org");
        users.setProfile(a.getId(), "Five", "Other@Example.org");
        assertThat(users.findByLoginOrEmail("OTHER@example.org"))
            .map(r -> r.getId())
            .contains(a.getId());
    }

    private void legacy(String login, String email) {
        db
            .insertInto(AUTHOR)
            .set(AUTHOR.LOGIN, login)
            .set(AUTHOR.SLUG, login)
            .set(AUTHOR.EMAIL, email)
            .set(AUTHOR.ROLE, "author")
            .execute();
    }

    @Test
    void createWithEmailDifferingOnlyInCaseIsAnEmailConflict() {
        users.create("six", "Case@example.org", "Six", Role.AUTHOR);
        assertThatThrownBy(() -> users.create("seven", "case@example.org", "Seven", Role.AUTHOR))
            .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(AuthorRepository.isEmailConflict(e))
                .as(e.getMessage())
                .isTrue());
    }

    @Test
    void setProfileOntoAnExistingEmailIsAnEmailConflictButALoginClashIsNot() {
        users.create("eight", "taken@example.org", "Eight", Role.AUTHOR);
        var nine = users.create("nine", "free@example.org", "Nine", Role.AUTHOR);
        assertThatThrownBy(() -> users.setProfile(nine.getId(), "Nine", "TAKEN@example.org"))
            .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(AuthorRepository.isEmailConflict(e))
                .as(e.getMessage())
                .isTrue());
        assertThatThrownBy(() -> users.create("eight", "other@example.org", "Dup", Role.AUTHOR))
            .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(AuthorRepository.isEmailConflict(e))
                .as(e.getMessage())
                .isFalse());
    }

    @Test
    void administratorsWithPassword() {
        var a = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        assertThat(users.countAdministrators()).isEqualTo(1);
        assertThat(users.anyAdministratorWithPassword()).isFalse();
        users.setPassword(a.getId(), TestStore.PASSWORD_HASH);
        assertThat(users.anyAdministratorWithPassword()).isTrue();
        users.setRole(a.getId(), Role.EDITOR);
        assertThat(users.countAdministrators()).isZero();
        assertThat(users.anyAdministratorWithPassword()).isFalse();
    }

    @Test
    void loginBeatsAnotherUsersEmail() {
        var byLogin = users.create("shared@example.org", "own@example.org", "Login", Role.AUTHOR);
        users.create("other", "Shared@example.org", "Email", Role.AUTHOR);
        assertThat(users.findByLoginOrEmail("shared@example.org"))
            .map(r -> r.getId())
            .contains(byLogin.getId());
    }

    @Test
    void slugSkipsTakenSuffixes() {
        users.create("john", "x@example.org", "X", Role.AUTHOR);
        users.create("john-2", "y@example.org", "Y", Role.AUTHOR);
        assertThat(users.create("John", "z@example.org", "Z", Role.AUTHOR).getSlug()).isEqualTo("john-3");
        // "_" in the base is a literal, not a LIKE wildcard
        users.create("axb 2", "w@example.org", "W", Role.AUTHOR);
        assertThat(users.create("a_b", "v@example.org", "V", Role.AUTHOR).getSlug()).isEqualTo("a_b");
    }
}
