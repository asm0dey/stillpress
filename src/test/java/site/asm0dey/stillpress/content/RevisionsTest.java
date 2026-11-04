package site.asm0dey.stillpress.content;

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
import site.asm0dey.stillpress.db.tables.records.RevisionRecord;
import site.asm0dey.stillpress.store.AuthorRepository;

@QuarkusTest
class RevisionsTest {
    @Inject
    Revisions revisions;
    @Inject
    ItemService items;
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    DSLContext db;
    long userId;

    @BeforeEach
    void reset() {
        store.reset();
        userId = users.create("writer", "writer@example.org", "Writer", Role.AUTHOR).getId();
    }

    @Test
    void keepsNewest25() {
        for (int i = 1; i <= 30; i++) {
            revisions.saved("item", 42, "save " + i, "body", "blocks", "", userId);
        }
        var kept = revisions.list("item", 42);
        assertThat(kept).hasSize(25);
        assertThat(kept.getFirst().getTitle()).isEqualTo("save 30");
        assertThat(kept.getLast().getTitle()).isEqualTo("save 6");
    }

    @Test
    void autosaveIsReplacedNotAccumulatedAndDeletedOnSave() {
        var item = items.create("post", userId);
        revisions.autosave("item", item.getId(), userId, "one", "b1", "");
        revisions.autosave("item", item.getId(), userId, "two", "b2", "");
        assertThat(db.fetchCount(REVISION, REVISION.AUTOSAVE.isTrue())).isEqualTo(1);
        assertThat(revisions.autosaveOf("item", item.getId(), userId).map(RevisionRecord::getTitle)).contains("two");
        items.update(
                item.getId(),
                new ItemService.Update(
                        "saved",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        item.getModifiedAt()
                ),
                new ItemService.Actor(userId, Role.AUTHOR)
        );
        assertThat(revisions.autosaveOf("item", item.getId(), userId)).isEmpty();
    }

    @Test
    void autosaveIsNotARevision() {
        revisions.autosave("item", 42, userId, "draft", "b", "");
        assertThat(revisions.list("item", 42)).isEmpty();
        assertThat(revisions.autosaveOf("item", 42, userId).map(RevisionRecord::getTitle)).contains("draft");
    }
}
