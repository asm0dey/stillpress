package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.REVISION;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Revision;

@QuarkusTest
class RevisionRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    RevisionRepository repo;
    @Inject
    DSLContext db;
    long userId;

    @BeforeEach
    void reset() {
        store.reset();
        userId = users.create("writer", "writer@example.org", "Writer", Role.AUTHOR).id();
    }

    @Test
    void pruneKeepsNewestNonAutosaveRevisions() {
        for (int i = 1; i <= 30; i++) {
            repo.insert("item", 42, "save " + i, "body", "blocks", "", userId, false);
        }
        repo.insert("item", 42, "draft", "body", null, "", userId, true);
        repo.prune("item", 42, 25);
        var kept = repo.list("item", 42);
        assertThat(kept).hasSize(25);
        assertThat(kept.getFirst().title()).isEqualTo("save 30");
        assertThat(kept.getLast().title()).isEqualTo("save 6");
        assertThat(repo.autosave("item", 42, userId)).map(Revision::title).contains("draft");
    }

    @Test
    void autosaveIsOnePerObjectAndUserAndNotListed() {
        repo.deleteAutosave("item", 42, userId);
        repo.insert("item", 42, "one", "b1", null, "", userId, true);
        repo.deleteAutosave("item", 42, userId);
        repo.insert("item", 42, "two", "b2", null, "", userId, true);
        assertThat(db.fetchCount(REVISION, REVISION.AUTOSAVE.isTrue())).isEqualTo(1);
        assertThat(repo.autosave("item", 42, userId)).map(Revision::title).contains("two");
        assertThat(repo.list("item", 42)).isEmpty();
        repo.deleteAutosave("item", 42, userId);
        assertThat(repo.autosave("item", 42, userId)).isEmpty();
    }
}
