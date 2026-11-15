package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.TestIdentities.user;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.Fixtures;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.ReusableBlockService;
import site.asm0dey.stillpress.content.Revisions;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.ReusableBlock;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

@ExtendWith(MockitoExtension.class)
class ReusableBlocksResourceTest {
    private static final long ME = 2;
    private static final String TYPE = ReusableBlockService.OBJECT_TYPE;
    private static final LocalDateTime T0 = Fixtures.T0;
    private static final ItemService.Actor ACTOR = new ItemService.Actor(ME, Role.AUTHOR);
    @Mock
    ReusableBlockService service;
    @Mock
    ReusableBlockRepository blocks;
    @Mock
    AuthorRepository authors;
    @Mock
    Revisions revisions;
    ReusableBlocksResource resource;

    @BeforeEach
    void setUp() {
        resource = new ReusableBlocksResource(service, blocks, authors, revisions, user(ME, "author2", Role.AUTHOR));
    }

    private static ReusableBlock block(long ref, String content, Long authorId) {
        return new ReusableBlock(ref + 100, ref, "T" + ref, content, T0, authorId);
    }

    private void authorsAre() {
        when(authors.all())
            .thenReturn(List.of(
                    new Author(1L, "author1", "Author One", null, null, null, "author", null, null, null),
                    new Author(ME, "author2", null, null, null, null, "author", null, null, null)
            ));
    }

    private static ItemService.Refused thrownBy(Runnable call) {
        return (ItemService.Refused) assertThatThrownBy(call::run).isInstanceOf(ItemService.Refused.class).actual();
    }

    @Test
    void listCarriesOwnerUsageAndCapabilityButNoContentByDefault() {
        authorsAre();
        when(blocks.all()).thenReturn(List.of(block(1, "one", 1L), block(2, "two", ME)));
        when(blocks.itemsUsing(Set.of(1L, 2L)))
            .thenReturn(List.of(
                    new ReusableBlockRepository.Use(10, "publish", Set.of(1L)),
                    new ReusableBlockRepository.Use(11, "draft", Set.of(1L, 2L)),
                    new ReusableBlockRepository.Use(12, "trash", Set.of(1L))
            ));
        assertThat(resource.list(false))
            .containsExactly(
                    new ReusableBlocksResource.BlockLine(1, "T1", null, "Author One", T0, 2, false),
                    new ReusableBlocksResource.BlockLine(2, "T2", null, "author2", T0, 1, true)
            );
    }

    @Test
    void listWithContentCarriesEachBlocksContent() {
        authorsAre();
        when(blocks.all()).thenReturn(List.of(block(1, "one", 1L), block(2, "two", ME)));
        when(blocks.itemsUsing(Set.of(1L, 2L))).thenReturn(List.of());
        assertThat(resource.list(true)).extracting(ReusableBlocksResource.BlockLine::content).containsExactly(
                "one",
                "two"
        );
    }

    @Test
    void getShowsAnAutosaveOnlyWhenItIsNewerThanTheBlock() {
        authorsAre();
        when(blocks.find(1)).thenReturn(Optional.of(block(1, "one", ME)));
        when(blocks.itemsUsing(Set.of(1L))).thenReturn(List.of());
        when(revisions.autosaveOf(TYPE, 1, ME)).thenReturn(Optional.of(Fixtures.revision(
                5,
                TYPE,
                1,
                true,
                T0.plusMinutes(1)
        )));
        assertThat(resource.get(1).autosave()).isEqualTo(new ItemsResource.AutosaveRef(T0.plusMinutes(1)));
        when(revisions.autosaveOf(TYPE, 1, ME)).thenReturn(Optional.of(Fixtures.revision(5, TYPE, 1, true, T0)));
        assertThat(resource.get(1).autosave()).isNull();
    }

    @Test
    void unknownBlockIs404() {
        when(blocks.find(9)).thenReturn(Optional.empty());
        assertThat(thrownBy(() -> resource.get(9)).status).isEqualTo(404);
        assertThat(thrownBy(() -> resource.autosave(9)).status).isEqualTo(404);
        assertThat(thrownBy(() -> resource.revisions(9)).status).isEqualTo(404);
    }

    @Test
    void createAnswers201AndNeedsABody() {
        authorsAre();
        var saved = block(3, "x", ME);
        when(service.save(null, "T3", "x", null, ACTOR)).thenReturn(saved);
        when(blocks.itemsUsing(Set.of(3L))).thenReturn(List.of());
        try (var r = resource.create(new ReusableBlocksResource.BlockSave("T3", "x", null))) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity()).isInstanceOfSatisfying(ReusableBlocksResource.BlockView.class, v -> {
                assertThat(v.id()).isEqualTo(3);
                assertThat(v.canEdit()).isTrue();
                assertThat(v.republished()).isNull();
            });
        }
        assertThat(thrownBy(() -> resource.create(null)).status).isEqualTo(400);
    }

    @Test
    void updateWithoutExpectedModifiedAtIs400AndWritesNothing() {
        var thrown = thrownBy(() -> resource.update(1, new ReusableBlocksResource.BlockSave("T", "four", null)));
        assertThat(thrown.body()).isEqualTo(Map.of("error", "invalid", "field", "expectedModifiedAt"));
        assertThat(thrownBy(() -> resource.update(1, null)).body()).isEqualTo(Map.of("error", "invalid"));
        verifyNoInteractions(service);
    }

    @Test
    void updateAnswersWithHowManyItemsItRepublished() {
        authorsAre();
        var b = block(1, "changed", ME);
        when(service.update(1, "T", "changed", T0, ACTOR)).thenReturn(new ReusableBlockService.Saved(b, 1));
        when(blocks.itemsUsing(Set.of(1L))).thenReturn(List.of(
                new ReusableBlockRepository.Use(10, "publish", Set.of(1L))
        ));
        var v = resource.update(1, new ReusableBlocksResource.BlockSave("T", "changed", T0));
        assertThat(v.republished()).isEqualTo(1);
        assertThat(v.usedBy()).isEqualTo(1);
        assertThat(v.content()).isEqualTo("changed");
    }

    @Test
    void aStaleSaveIsTheServicesConflictAsA409Body() {
        when(service.update(1, "T", "x", T0, ACTOR)).thenThrow(new ItemService.Refused(409, "conflict", null));
        var thrown = thrownBy(() -> resource.update(1, new ReusableBlocksResource.BlockSave("T", "x", T0)));
        try (var r = resource.refused(thrown)) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "conflict"));
        }
    }

    @Test
    void anUnsafeSaveIsA400WithItsViolations() {
        var refused = new ItemService.Refused(400, "unsafe_html", "content", List.of("<script> element"));
        try (var r = resource.refused(refused)) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity())
                .isEqualTo(Map.of("error", "unsafe_html", "field", "content", "violations", List.of("<script> element"))
                );
        }
    }

    @Test
    void autosaveIsPassedToTheServiceAndNeedsABody() {
        try (var r = resource.autosave(1, new ReusableBlocksResource.Draft("T", "draft"))) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(service).autosave(1, ACTOR, "T", "draft");
        assertThat(thrownBy(() -> resource.autosave(1, null)).status).isEqualTo(400);
    }

    @Test
    void autosaveReadIsTheUsersOwnOrA404() {
        when(blocks.find(1)).thenReturn(Optional.of(block(1, "one", ME)));
        when(revisions.autosaveOf(TYPE, 1, ME)).thenReturn(Optional.empty());
        assertThat(thrownBy(() -> resource.autosave(1)).status).isEqualTo(404);
        when(revisions.autosaveOf(TYPE, 1, ME)).thenReturn(Optional.of(Fixtures.revision(5, TYPE, 1, true, T0)));
        assertThat(resource.autosave(1)).isEqualTo(new ReusableBlocksResource.AutosaveView("rt", "rb", T0));
    }

    @Test
    void revisionsListTheNamedAuthors() {
        authorsAre();
        when(blocks.find(1)).thenReturn(Optional.of(block(1, "one", ME)));
        when(revisions.list(TYPE, 1)).thenReturn(List.of(Fixtures.revision(5, TYPE, 1, false, T0, 1L)));
        assertThat(resource.revisions(1)).containsExactly(new ItemsResource.RevisionLine(5, "Author One", T0));
    }

    @Test
    void aRevisionIsOnlyOfThisBlockAndNeverAnAutosave() {
        authorsAre();
        when(revisions.find(5)).thenReturn(Optional.of(Fixtures.revision(5, TYPE, 1, false, T0)));
        when(revisions.find(6)).thenReturn(Optional.of(Fixtures.revision(6, TYPE, 2, false, T0)));
        when(revisions.find(7)).thenReturn(Optional.of(Fixtures.revision(7, "item", 1, false, T0)));
        when(revisions.find(8)).thenReturn(Optional.of(Fixtures.revision(8, TYPE, 1, true, T0)));
        assertThat(resource.revision(1, 5).title()).isEqualTo("rt");
        for (var rid : new long[] {6, 7, 8, 99}) {
            assertThat(thrownBy(() -> resource.revision(1, rid)).status).as("revision %d", rid).isEqualTo(404);
        }
    }

    @Test
    void restoringARevisionAnswersWithTheSavedBlock() {
        authorsAre();
        when(service.restoreRevision(1, 5, ACTOR)).thenReturn(new ReusableBlockService.Saved(block(1, "one", ME), 0));
        when(blocks.itemsUsing(Set.of(1L))).thenReturn(List.of());
        var v = resource.restoreRevision(1, 5);
        assertThat(v.content()).isEqualTo("one");
        assertThat(v.republished()).isZero();
    }
}
