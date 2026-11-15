package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Term;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ExtendWith(MockitoExtension.class)
class TermServiceTest {
    @Mock
    TermRepository terms;
    @Mock
    SettingRepository settings;
    @Mock
    Permalinker permalinks;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    ImportGuard guard;
    @Mock
    FragmentRepository fragments;
    @Mock
    RedirectRepository redirects;
    TermService service;
    ItemService.Actor editor = new ItemService.Actor(1, Role.EDITOR);
    ItemService.Actor author = new ItemService.Actor(2, Role.AUTHOR);
    ItemService.Actor contributor = new ItemService.Actor(3, Role.CONTRIBUTOR);

    @BeforeEach
    void setUp() {
        service = new TermService(terms, settings, permalinks, queue, jobs, guard, fragments, redirects);
    }

    private static Term term(long id, String slug, Long parentId) {
        return new Term(id, null, "category", slug, slug, parentId, null);
    }

    private static void refusedWith(Runnable call, int status, String error, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ItemService.Refused.class, e -> {
            assertThat(e.status).isEqualTo(status);
            assertThat(e.error).isEqualTo(error);
            assertThat(e.field).isEqualTo(field);
        });
    }

    @Test
    void contributorMayNotCreateATerm() {
        refusedWith(() -> service.createTerm("post_tag", "Java", null, contributor), 403, "forbidden", null);
        verify(terms, never()).create("post_tag", "Java", null);
    }

    @Test
    void unknownTaxonomyAndOverlongOrEmptyNamesAre400() {
        refusedWith(() -> service.createTerm("nav_menu", "Java", null, author), 400, "invalid", "taxonomy");
        refusedWith(() -> service.createTerm("category", "n".repeat(201), null, author), 400, "invalid", "name");
        refusedWith(() -> service.createTerm("category", "  ", null, author), 400, "invalid", "name");
        refusedWith(() -> service.createTerm("category", null, null, author), 400, "invalid", "name");
        verify(terms, never()).create("category", "n".repeat(201), null);
    }

    @Test
    void tagsHaveNoParentAndACategoryParentMustBeACategory() {
        refusedWith(() -> service.createTerm("post_tag", "Java", 5L, author), 400, "invalid", "parentId");
        when(terms.findById(5)).thenReturn(Optional.empty());
        refusedWith(() -> service.createTerm("category", "Java", 5L, author), 400, "invalid", "parentId");
    }

    @Test
    void aNameOfExactlyTheColumnWidthIsAccepted() {
        var name = "n".repeat(200);
        when(terms.create("category", name, null)).thenReturn(term(1, name, null));
        when(settings.get("category_base")).thenReturn(Optional.empty());
        when(permalinks.termPath(1)).thenReturn(name);
        assertThat(service.createTerm("category", name, null, author).id()).isEqualTo(1);
        verify(queue).base("/category/" + name + "/");
        verify(jobs).requestPublish();
    }

    @Test
    void authorMayNotEditOrDeleteATerm() {
        refusedWith(
                () -> service.updateTerm(1, new TermService.Change("Children", "children", null, null), author),
                403,
                "forbidden",
                null
        );
        refusedWith(() -> service.deleteTerm(1, author), 403, "forbidden", null);
        verifyNoInteractions(guard, redirects);
        verify(terms, never()).update(anyLong(), any(), any(), any(), any());
    }

    @Test
    void renameToAnOverlongNameIs400() {
        when(terms.findById(1)).thenReturn(Optional.of(term(1, "news", null)));
        refusedWith(
                () -> service.updateTerm(1, new TermService.Change("n".repeat(201), "news", null, null), editor),
                400,
                "invalid",
                "name"
        );
        refusedWith(() -> service.updateTerm(1, null, editor), 400, "invalid", "name");
    }

    @Test
    void aTermCannotBecomeItsOwnOrItsDescendantsChild() {
        var travel = term(1, "travel", null);
        when(terms.findById(1)).thenReturn(Optional.of(travel));
        when(terms.byTaxonomy("category")).thenReturn(List.of(travel, term(2, "europe", 1L)));
        refusedWith(
                () -> service.updateTerm(1, new TermService.Change("Travel", "travel", 2L, null), editor),
                400,
                "invalid",
                "parentId"
        );
        refusedWith(
                () -> service.updateTerm(1, new TermService.Change("Travel", "travel", 1L, null), editor),
                400,
                "invalid",
                "parentId"
        );
        refusedWith(
                () -> service.updateTerm(1, new TermService.Change("Travel", "travel", 99L, null), editor),
                400,
                "invalid",
                "parentId"
        );
    }

    @Test
    void aSlugOfAnotherTermIs409() {
        var news = term(2, "news", null);
        when(terms.findById(2)).thenReturn(Optional.of(news));
        when(terms.byTaxonomy("category")).thenReturn(List.of(term(1, "travel", null), news));
        when(terms.findBySlug("category", "travel")).thenReturn(Optional.of(term(1, "travel", null)));
        refusedWith(
                () -> service.updateTerm(2, new TermService.Change("News", "travel", null, null), editor),
                409,
                "slug_taken",
                "slug"
        );
        verifyNoInteractions(guard);
    }

    @Test
    void theDefaultCategoryCannotBeDeleted() {
        when(terms.findById(2)).thenReturn(Optional.of(term(2, "news", null)));
        when(settings.get("default_category")).thenReturn(Optional.of("2"));
        refusedWith(() -> service.deleteTerm(2, editor), 409, "default_category", null);
        verify(terms, never()).delete(anyLong(), any());
    }

    @Test
    void anUnknownTermIs404() {
        when(terms.findById(7)).thenReturn(Optional.empty());
        refusedWith(() -> service.deleteTerm(7, editor), 404, "not_found", null);
        refusedWith(
                () -> service.updateTerm(7, new TermService.Change("A", "a", null, null), editor),
                404,
                "not_found",
                null
        );
    }
}
