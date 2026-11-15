package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.TestIdentities.user;
import module java.base;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.Fixtures;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.Permalinker;
import site.asm0dey.stillpress.content.Revisions;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.Term;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ExtendWith(MockitoExtension.class)
class ItemsResourceTest {
    private static final LocalDateTime T0 = Fixtures.T0;
    @Mock
    ItemService service;
    @Mock
    Permalinker permalinks;
    @Mock
    ItemRepository items;
    @Mock
    TermRepository terms;
    @Mock
    AuthorRepository authors;
    @Mock
    Revisions revisions;

    private ItemsResource as(long id, Role role) {
        return as(user(id, "u" + id, role));
    }

    private ItemsResource as(SecurityIdentity identity) {
        return new ItemsResource(service, permalinks, items, terms, authors, revisions, identity);
    }

    private static ItemService.Refused thrownBy(Runnable call) {
        return (ItemService.Refused) assertThatThrownBy(call::run).isInstanceOf(ItemService.Refused.class).actual();
    }

    private void namedAuthors() {
        when(authors.all())
            .thenReturn(List.of(
                    new Author(1L, "author1", "Author One", null, null, null, "author", null, null, null),
                    new Author(3L, "editor", null, null, null, null, "editor", null, null, null)
            ));
    }

    private ItemRepository.Filter listed(ItemRepository.Filter filter) {
        when(items.list(filter, 0, 20)).thenReturn(List.of());
        when(items.count(filter)).thenReturn(0);
        when(items.countsByStatus(filter)).thenReturn(Map.of());
        return filter;
    }

    @Test
    void anAuthorOrContributorListsOnlyTheirOwnEvenWithoutMine() {
        var filter = listed(new ItemRepository.Filter("post", null, 1L, null));
        as(1, Role.AUTHOR).list("post", "any", false, null, 1);
        as(1, Role.CONTRIBUTOR).list("post", null, false, null, 1);
        verify(items, times(2)).list(filter, 0, 20);
    }

    @Test
    void anEditorListsEverythingUnlessMineIsAsked() {
        var all = listed(new ItemRepository.Filter("page", "publish", null, "tips"));
        var mine = listed(new ItemRepository.Filter("page", "publish", 3L, "tips"));
        as(3, Role.EDITOR).list("page", "publish", false, "tips", 1);
        as(3, Role.EDITOR).list("page", "publish", true, "tips", 1);
        verify(items).list(all, 0, 20);
        verify(items).list(mine, 0, 20);
    }

    @Test
    void listIsPagedTwentyAtATimeAndCarriesTotalAndCounts() {
        var filter = new ItemRepository.Filter("post", null, null, null);
        var row = new ItemRepository.Row(1, "post", "Out", "publish", "a", T0, T0, "/out/");
        when(items.list(filter, 40, 20)).thenReturn(List.of(row));
        when(items.list(filter, 0, 20)).thenReturn(List.of());
        when(items.count(filter)).thenReturn(41);
        when(items.countsByStatus(filter)).thenReturn(Map.of("publish", 41));
        assertThat(as(3, Role.EDITOR).list("post", "any", false, null, 3))
            .isEqualTo(new ItemsResource.ItemList(List.of(row), 41, Map.of("publish", 41)));
        assertThat(as(3, Role.EDITOR).list("post", "any", false, null, -5).items()).isEmpty();
    }

    @Test
    void anUnknownTypeIs400() {
        var thrown = thrownBy(() -> as(3, Role.EDITOR).list("attachment", null, false, null, 1));
        assertThat(thrown.body()).isEqualTo(Map.of("error", "invalid", "field", "type"));
        verifyNoInteractions(items);
    }

    @Test
    void anAuthorCannotCreateAPageButCanCreateAPost() {
        var thrown = thrownBy(() -> as(1, Role.AUTHOR).create(new ItemsResource.NewItem("page")));
        assertThat(thrown.status).isEqualTo(403);
        verifyNoInteractions(service);
        namedAuthors();
        var draft = Fixtures.item(5, "post", "draft", 1L);
        when(service.create("post", 1)).thenReturn(draft);
        when(terms.forItem(5)).thenReturn(List.of());
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.empty());
        try (var r = as(1, Role.AUTHOR).create(new ItemsResource.NewItem("post"))) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity()).isInstanceOfSatisfying(ItemsResource.ItemView.class, v -> {
                assertThat(v.id()).isEqualTo(5);
                assertThat(Map.of("canEdit", v.canEdit())).containsExactly(Map.entry("canEdit", true));
            });
        }
    }

    @Test
    void createNeedsAKnownTypeInTheBody() {
        assertThat(thrownBy(() -> as(1, Role.AUTHOR).create(new ItemsResource.NewItem("x"))).field).isEqualTo("type");
        verifyNoInteractions(service);
    }

    @Test
    void createWithoutABodyOrTypeIs400NotACrash() {
        var resource = as(1, Role.AUTHOR);
        for (var body : Arrays.asList(null, new ItemsResource.NewItem(null))) {
            assertThat(thrownBy(() -> resource.create(body)))
                .extracting(r -> r.status, r -> r.field)
                .containsExactly(400, "type");
        }
        verifyNoInteractions(service);
    }

    @Test
    void getBuildsTheViewFromTermsAuthorAndAutosave() {
        namedAuthors();
        var stored = Fixtures.item(5, "post", "publish", 1L);
        when(items.find(5)).thenReturn(Optional.of(stored));
        when(permalinks.localDate(stored)).thenReturn(T0.plusHours(3));
        when(terms.forItem(5))
            .thenReturn(List.of(
                    new Term(10L, null, "category", "C", "c", null, null),
                    new Term(11L, null, "post_tag", "T", "t", null, null),
                    new Term(12L, null, "post_tag", "U", "u", null, null)
            ));
        when(revisions.autosaveOf("item", 5, 1))
            .thenReturn(Optional.of(Fixtures.revision(9, "item", 5, true, T0.plusMinutes(1))));
        var v = as(1, Role.AUTHOR).get(5);
        assertThat(v.categories()).containsExactly(10L);
        assertThat(v.tags()).containsExactly(11L, 12L);
        assertThat(v.author()).isEqualTo("Author One");
        assertThat(v.date()).isEqualTo(T0.plusHours(3));
        assertThat(v.autosave()).isEqualTo(new ItemsResource.AutosaveRef(T0.plusMinutes(1)));
        assertThat(Map.of("canEdit", v.canEdit())).containsExactly(Map.entry("canEdit", true));
    }

    @Test
    void anAutosaveOlderThanTheLastSaveIsNotOffered() {
        namedAuthors();
        when(items.find(5)).thenReturn(Optional.of(Fixtures.item(5, "post", "draft", 1L)));
        when(terms.forItem(5)).thenReturn(List.of());
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.of(Fixtures.revision(9, "item", 5, true, T0)));
        assertThat(as(1, Role.AUTHOR).get(5).autosave()).isNull();
    }

    @Test
    void getOfAnUnknownItemIs404AndOfSomeoneElsesPostByAnAuthorIs403() {
        when(items.find(9)).thenReturn(Optional.empty());
        assertThat(thrownBy(() -> as(1, Role.AUTHOR).get(9)).status).isEqualTo(404);
        when(items.find(5)).thenReturn(Optional.of(Fixtures.item(5, "post", "draft", 1L)));
        assertThat(thrownBy(() -> as(2, Role.AUTHOR).get(5)).status).isEqualTo(403);
        verifyNoInteractions(terms);
    }

    @Test
    void updateIsTheServicesResultAndNeedsABody() {
        namedAuthors();
        var u = new ItemService.Update("T", null, null, null, null, null, null, null, null, null, null, null, null, T0);
        var saved = Fixtures.item(5, "post", "draft", 1L);
        when(service.update(5, u, new ItemService.Actor(1, Role.AUTHOR))).thenReturn(saved);
        when(terms.forItem(5)).thenReturn(List.of());
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.empty());
        assertThat(as(1, Role.AUTHOR).update(5, u).title()).isEqualTo("Title 5");
        assertThat(thrownBy(() -> as(1, Role.AUTHOR).update(5, null)).body()).isEqualTo(Map.of("error", "invalid"));
    }

    @Test
    void trashAnswers204AndRestoreReturnsTheDraftView() {
        namedAuthors();
        try (var r = as(1, Role.AUTHOR).trash(5)) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(service).trash(5, new ItemService.Actor(1, Role.AUTHOR));
        var draft = Fixtures.item(5, "post", "draft", 1L);
        when(items.find(5)).thenReturn(Optional.of(draft));
        when(terms.forItem(5)).thenReturn(List.of());
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.empty());
        assertThat(as(1, Role.AUTHOR).restore(5).status()).isEqualTo("draft");
        verify(service).restore(5, new ItemService.Actor(1, Role.AUTHOR));
    }

    @Test
    void autosaveWriteGoesToTheServiceAndNeedsABody() {
        try (var r = as(1, Role.AUTHOR).autosave(5, new ItemsResource.Draft("T", "b", "e"))) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(service).autosave(5, new ItemService.Actor(1, Role.AUTHOR), "T", "b", "e");
        assertThat(thrownBy(() -> as(1, Role.AUTHOR).autosave(5, null)).status).isEqualTo(400);
    }

    @Test
    void autosaveReadIsTheUsersOwnAndOtherAuthorsGet403Not404() {
        when(items.find(5)).thenReturn(Optional.of(Fixtures.item(5, "post", "draft", 1L)));
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.empty());
        assertThat(thrownBy(() -> as(1, Role.AUTHOR).autosave(5)).status).isEqualTo(404);
        assertThat(thrownBy(() -> as(2, Role.AUTHOR).autosave(5)).status).isEqualTo(403);
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.of(Fixtures.revision(9, "item", 5, true, T0)));
        assertThat(as(1, Role.AUTHOR).autosave(5)).isEqualTo(new ItemsResource.AutosaveView("rt", "rb", "re", T0));
    }

    @Test
    void revisionsAreListedWithAuthorNamesAndOnlyForViewers() {
        namedAuthors();
        when(items.find(5)).thenReturn(Optional.of(Fixtures.item(5, "post", "draft", 1L)));
        when(revisions.list("item", 5)).thenReturn(List.of(Fixtures.revision(7, "item", 5, false, T0)));
        assertThat(as(1, Role.AUTHOR).revisions(5)).containsExactly(new ItemsResource.RevisionLine(7, "Author One", T0));
        assertThat(thrownBy(() -> as(2, Role.AUTHOR).revisions(5)).status).isEqualTo(403);
    }

    @Test
    void aRevisionIsOnlyAnItemsNonAutosaveOneOfAnItemTheUserMayView() {
        namedAuthors();
        when(items.find(5)).thenReturn(Optional.of(Fixtures.item(5, "post", "draft", 1L)));
        when(revisions.find(7)).thenReturn(Optional.of(Fixtures.revision(7, "item", 5, false, T0)));
        when(revisions.find(8)).thenReturn(Optional.of(Fixtures.revision(8, "item", 5, true, T0)));
        when(revisions.find(9)).thenReturn(Optional.of(Fixtures.revision(9, "reusable_block", 5, false, T0)));
        assertThat(as(1, Role.AUTHOR).revision(7))
            .isEqualTo(new ItemsResource.RevisionView(7, "rt", "rb", "re", T0, "Author One"));
        assertThat(thrownBy(() -> as(2, Role.AUTHOR).revision(7)).status).isEqualTo(403);
        for (var rid : new long[] {8, 9, 99}) {
            assertThat(thrownBy(() -> as(1, Role.AUTHOR).revision(rid)).status).as("revision %d", rid).isEqualTo(404);
        }
    }

    @Test
    void restoringARevisionReturnsTheSavedItemView() {
        namedAuthors();
        var saved = Fixtures.item(5, "post", "publish", 1L);
        when(service.restoreRevision(5, 7, new ItemService.Actor(1, Role.AUTHOR))).thenReturn(saved);
        when(terms.forItem(5)).thenReturn(List.of());
        when(revisions.autosaveOf("item", 5, 1)).thenReturn(Optional.empty());
        var v = as(1, Role.AUTHOR).restoreRevision(5, 7);
        assertThat(v.status()).isEqualTo("publish");
        assertThat(v.permalink()).isEqualTo("/slug-5/");
    }

    @Test
    void aRefusalIsItsStatusWithTheFullBody() {
        var refused = new ItemService.Refused(400, "unsafe_html", "body", List.of("onclick attribute on <p>"));
        try (var r = as(1, Role.AUTHOR).refused(refused)) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity())
                .isEqualTo(Map.of(
                        "error",
                        "unsafe_html",
                        "field",
                        "body",
                        "violations",
                        List.of("onclick attribute on <p>")
                ));
        }
        try (var r = as(1, Role.AUTHOR).refused(new ItemService.Refused(409, "conflict", null))) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "conflict"));
        }
    }

    @Test
    void theActorIsTheSessionsUserIdAndRole() {
        assertThat(ItemsResource.actor(user(8, "x", Role.CONTRIBUTOR))).isEqualTo(
                new ItemService.Actor(8, Role.CONTRIBUTOR)
        );
    }
}
