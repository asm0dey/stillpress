package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.max;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

@QuarkusTest
class ReusableBlockServiceTest {
    @Inject
    TestStore store;
    @Inject
    ReusableBlockService service;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    ItemService items;
    @Inject
    AuthorRepository users;
    @Inject
    DSLContext db;
    ItemService.Actor editor;
    ItemService.Actor author1;
    ItemService.Actor author2;
    ItemService.Actor contributor;

    @BeforeEach
    void reset() {
        store.reset();
        editor = actor("editor", Role.EDITOR);
        author1 = actor("author1", Role.AUTHOR);
        author2 = actor("author2", Role.AUTHOR);
        contributor = actor("contributor", Role.CONTRIBUTOR);
    }

    @AfterEach
    void settle() {
        TestStore.await(() -> db.fetchCount(SCHEDULED_TASKS, SCHEDULED_TASKS.TASK_NAME.eq(Jobs.PUBLISH_SITE)) == 0, 30);
    }

    @Test
    void newBlockIdContinuesAfterImportedOnes() throws Exception {
        store.importSample();
        long imported =
                db
            .select(max(REUSABLE_BLOCK.SOURCE_ID))
            .from(REUSABLE_BLOCK)
            .fetchSingle(max(REUSABLE_BLOCK.SOURCE_ID));
        var created = service.save(null, "New", "<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->", null, editor);
        assertThat(created.getSourceId()).isEqualTo(imported + 1);
    }

    @Test
    void editEnqueuesEveryItemUsingItIncludingNested() {
        var b = service.save(null, "B", "<!-- wp:paragraph --><p>b</p><!-- /wp:paragraph -->", null, editor);
        var a = service.save(null, "A", "<!-- wp:block {\"ref\":" + b.getSourceId() + "} /-->", null, editor);
        var x = publishedItem("<!-- wp:block {\"ref\":" + a.getSourceId() + "} /-->");
        var y = publishedItem("<!-- wp:block {\"ref\":" + b.getSourceId() + "} /-->");
        var z = publishedItem("<!-- wp:paragraph --><p>z</p><!-- /wp:paragraph -->");
        db.deleteFrom(PUBLISH_QUEUE).execute();
        assertThat(queuedAfterSaving(b.getSourceId(), editor))
            .containsExactlyInAnyOrder(String.valueOf(x), String.valueOf(y))
            .doesNotContain(String.valueOf(z));
    }

    @Test
    void refPrefixDoesNotMatch() {
        ownerless(12);
        ownerless(123);
        var item = publishedItem("<!-- wp:block {\"ref\":123} /-->");
        db.deleteFrom(PUBLISH_QUEUE).execute();
        assertThat(queuedAfterSaving(12, editor)).isEmpty();
        assertThat(queuedAfterSaving(123, editor)).containsExactly(String.valueOf(item));
    }

    @Test
    void refWithWhitespaceStillMatches() {
        ownerless(7);
        var item = publishedItem("<!-- wp:block {\"ref\": 7} /-->");
        db.deleteFrom(PUBLISH_QUEUE).execute();
        assertThat(queuedAfterSaving(7, editor)).containsExactly(String.valueOf(item));
    }

    @Test
    void authorEditsOwnBlockButNotOthers() {
        var b = service.save(null, "Mine", "one", null, author1);
        assertThat(b.getAuthorId()).isEqualTo(author1.userId());
        var saved = service.save(b.getSourceId(), "Mine", "two", b.getModifiedAt(), author1);
        assertThat(saved.getContent()).isEqualTo("two");
        assertThatThrownBy(() -> service.save(b.getSourceId(), "Taken", "three", saved.getModifiedAt(), author2))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(403));
        assertThat(blocks.find(b.getSourceId()).orElseThrow().getContent()).isEqualTo("two");
    }

    @Test
    void contributorCannotCreate() {
        assertThatThrownBy(() -> service.save(null, "No", "x", null, contributor))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(403));
        assertThat(db.fetchCount(REUSABLE_BLOCK)).isZero();
    }

    @Test
    void staleExpectedModifiedAtIs409() {
        var b = service.save(null, "T", "one", null, author1);
        service.save(b.getSourceId(), "T", "two", b.getModifiedAt(), author1);
        assertThatThrownBy(() -> service.save(b.getSourceId(), "T", "three", b.getModifiedAt(), author1))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(409));
        assertThat(blocks.find(b.getSourceId()).orElseThrow().getContent()).isEqualTo("two");
        assertThatThrownBy(() -> service.save(b.getSourceId(), "T", "four", null, author1))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(409));
        assertThat(blocks.find(b.getSourceId()).orElseThrow().getContent()).isEqualTo("two");
    }

    @Test
    void ownerlessImportedBlockOnlyForEditors() {
        ownerless(500);
        assertThatThrownBy(() -> service.save(500L, "T", "by author", null, author1))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(403));
        assertThat(service.save(500L, "T", "by editor", null, editor).getContent()).isEqualTo("by editor");
    }

    private List<String> queuedAfterSaving(long ref, ItemService.Actor a) {
        // read inside the save's transaction: the publish it requests runs after commit and drains the queue
        return QuarkusTransaction.requiringNew().call(() -> {
            var b = blocks.find(ref).orElseThrow();
            service.save(ref, b.getTitle(), b.getContent() + " ", b.getModifiedAt(), a);
            return db
                .select(PUBLISH_QUEUE.REF)
                .from(PUBLISH_QUEUE)
                .where(PUBLISH_QUEUE.KIND.eq("item"))
                .fetch(PUBLISH_QUEUE.REF);
        });
    }

    private long publishedItem(String body) {
        var id = items.create("post", editor.userId()).getId();
        db
            .update(ITEM)
            .set(ITEM.BODY, body)
            .set(ITEM.STATUS, "publish")
            .set(ITEM.SLUG, "p" + id)
            .set(ITEM.PERMALINK, "/p" + id + "/")
            .set(ITEM.PUBLISHED_AT, LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
            .where(ITEM.ID.eq(id))
            .execute();
        return id;
    }

    private void ownerless(long sourceId) {
        db
            .insertInto(REUSABLE_BLOCK, REUSABLE_BLOCK.SOURCE_ID, REUSABLE_BLOCK.TITLE, REUSABLE_BLOCK.CONTENT)
            .values(sourceId, "Imported " + sourceId, "imported")
            .execute();
    }

    private ItemService.Actor actor(String login, Role role) {
        return new ItemService.Actor(users.create(login, login + "@example.org", login, role).getId(), role);
    }
}
