package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.db.tables.pojos.Item;

@QuarkusTest
class ItemRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    ItemRepository items;
    @Inject
    ItemService service;
    @Inject
    AuthorRepository users;
    long author1;
    long author2;

    @BeforeEach
    void reset() {
        store.reset();
        author1 = users.create("author1", "a1@example.org", "Author One", Role.AUTHOR).id();
        author2 = users.create("author2", "a2@example.org", "Author Two", Role.AUTHOR).id();
    }

    private Item draft(String type, long author, String title, String status) {
        var id = service.create(type, author).id();
        return items.update(
                id,
                new ItemRepository.Edit(
                        status,
                        title,
                        null,
                        "",
                        "blocks",
                        "",
                        author,
                        null,
                        null,
                        null,
                        "open",
                        0,
                        LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
                )
        );
    }

    private static ItemService.Update title(String title, LocalDateTime expectedModifiedAt) {
        return new ItemService.Update(
                title,
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
                expectedModifiedAt
        );
    }

    @Test
    void staleExpectedModifiedAtIsRefused() {
        var actor = new ItemService.Actor(author1, Role.AUTHOR);
        var created = service.create("post", author1);
        var first = service.update(created.id(), title("A", created.modifiedAt()), actor);
        // what the database stores comes back equal to what the first save wrote, so the next save can quote it
        assertThat(items.find(created.id()).orElseThrow().modifiedAt()).isEqualTo(first.modifiedAt());
        assertThatThrownBy(() -> service.update(created.id(), title("B", created.modifiedAt()), actor))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> {
                assertThat(e.status).isEqualTo(409);
                assertThat(e.error).isEqualTo("conflict");
            });
        assertThat(items.find(created.id()).orElseThrow().title()).isEqualTo("A");
        var second = service.update(created.id(), title("C", first.modifiedAt()), actor);
        assertThat(second.title()).isEqualTo("C");
    }

    @Test
    void slugTakenSeesOtherItemsOnly() {
        var post = draft("post", author1, "One", "draft");
        var other = draft("post", author1, "Two", "draft");
        var page = draft("page", author1, "Three", "draft");
        items.setPath(post.id(), "same", "/2024/01/01/same/");
        items.setPath(page.id(), "page-slug", "/page-slug/");
        assertThat(items.slugTaken("post", "same", null)).isTrue();
        // an item never collides with itself
        assertThat(items.slugTaken("post", "same", post.id())).isFalse();
        assertThat(items.slugTaken("post", "same", other.id())).isTrue();
        // the slug is unique per type, whatever the date
        assertThat(items.slugTaken("page", "same", null)).isFalse();
        assertThat(items.slugTaken("post", "page-slug", null)).isFalse();
        assertThat(items.slugTaken("post", "unused", null)).isFalse();
    }

    @Test
    void permalinkTakenSeesOtherItemsOfAnyType() {
        var post = draft("post", author1, "One", "draft");
        var page = draft("page", author1, "Two", "draft");
        items.setPath(post.id(), "same", "/same/");
        assertThat(items.permalinkTaken("/same/", null)).isTrue();
        assertThat(items.permalinkTaken("/same/", post.id())).isFalse();
        assertThat(items.permalinkTaken("/same/", page.id())).isTrue();
        assertThat(items.permalinkTaken("/other/", null)).isFalse();
    }

    @Test
    void findByPermalinkReturnsTheItemAtThatPath() {
        var post = draft("post", author1, "One", "publish");
        items.setPath(post.id(), "live", "/live/");
        assertThat(items.findByPermalink("/live/")).map(Item::id).contains(post.id());
        assertThat(items.findByPermalink("/none/")).isEmpty();
    }

    @Test
    void listFiltersByAuthorStatusAndSearchAndCountsByStatus() {
        draft("post", author1, "Out", "publish");
        draft("post", author1, "Draft one", "draft");
        draft("post", author2, "Someone else", "draft");
        draft("page", author1, "A page", "draft");
        var trashed = draft("post", author1, "Gone", "trash");
        assertThat(trashed.status()).isEqualTo("trash");
        var mine = new ItemRepository.Filter("post", null, author1, null);
        // newest modified first; the trash is outside "every status"
        assertThat(items.list(mine, 0, 20)).extracting(ItemRepository.Row::title).containsExactly("Draft one", "Out");
        assertThat(items.count(mine)).isEqualTo(2);
        assertThat(items.countsByStatus(mine)).isEqualTo(Map.of("draft", 1, "publish", 1, "trash", 1));
        var published = new ItemRepository.Filter("post", "publish", author1, null);
        assertThat(items.list(published, 0, 20)).extracting(ItemRepository.Row::title).containsExactly("Out");
        var everyone = new ItemRepository.Filter("post", null, null, null);
        assertThat(items.count(everyone)).isEqualTo(3);
        assertThat(items.countsByStatus(everyone)).isEqualTo(Map.of("draft", 2, "publish", 1, "trash", 1));
        var search = new ItemRepository.Filter("post", null, null, "SOMEONE");
        assertThat(items.list(search, 0, 20)).extracting(ItemRepository.Row::title).containsExactly("Someone else");
        assertThat(items.list(everyone, 1, 1)).hasSize(1);
        assertThat(items.count(new ItemRepository.Filter("post", null, author2, "nothing"))).isZero();
    }
}
