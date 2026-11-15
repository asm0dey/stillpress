package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;

@QuarkusTest
class ReusableBlockRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    ItemRepository items;
    @Inject
    AuthorRepository users;
    @Inject
    DSLContext db;
    long userId;

    @BeforeEach
    void reset() {
        store.reset();
        userId = users.create("editor", "editor@example.org", "Editor", Role.EDITOR).id();
    }

    @Test
    void newBlockIdContinuesAfterImportedOnes() throws Exception {
        store.importSample();
        long imported = db.select(DSL.max(REUSABLE_BLOCK.SOURCE_ID)).from(REUSABLE_BLOCK).fetchSingle().value1();
        var created =
                blocks.create("New", "<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->", userId, LocalDateTime.now());
        assertThat(created.sourceId()).isEqualTo(imported + 1);
    }

    @Test
    void refPrefixDoesNotMatch() {
        ownerless(12);
        ownerless(123);
        var item = itemWithBody("<!-- wp:block {\"ref\":123} /-->");
        assertThat(blocks.itemsUsing(Set.of(12L))).isEmpty();
        assertThat(blocks.itemsUsing(Set.of(123L))).extracting(ReusableBlockRepository.Use::itemId).containsExactly(
                item
        );
    }

    @Test
    void refWithWhitespaceStillMatches() {
        ownerless(7);
        var item = itemWithBody("<!-- wp:block {\"ref\": 7} /-->");
        assertThat(blocks.itemsUsing(Set.of(7L))).extracting(ReusableBlockRepository.Use::itemId).containsExactly(item);
    }

    private long itemWithBody(String body) {
        var id = items.create("post", userId, "http://localhost", "open", LocalDateTime.now()).id();
        db.update(ITEM).set(ITEM.BODY, body).where(ITEM.ID.eq(id)).execute();
        return id;
    }

    private void ownerless(long sourceId) {
        db
            .insertInto(REUSABLE_BLOCK, REUSABLE_BLOCK.SOURCE_ID, REUSABLE_BLOCK.TITLE, REUSABLE_BLOCK.CONTENT)
            .values(sourceId, "Imported " + sourceId, "imported")
            .execute();
    }
}
