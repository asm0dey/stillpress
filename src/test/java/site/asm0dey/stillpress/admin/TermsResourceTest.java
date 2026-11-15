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
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.TermService;
import site.asm0dey.stillpress.db.tables.pojos.Term;
import site.asm0dey.stillpress.store.TermRepository;

@ExtendWith(MockitoExtension.class)
class TermsResourceTest {
    private static final ItemService.Actor EDITOR = new ItemService.Actor(7, Role.EDITOR);
    @Mock
    TermService service;
    @Mock
    TermRepository terms;
    TermsResource resource;

    @BeforeEach
    void setUp() {
        resource = new TermsResource(service, terms, user(7, "ed", Role.EDITOR));
    }

    private static Term term(long id, String name, String slug, Long parentId) {
        return new Term(id, null, "category", name, slug, parentId, "d");
    }

    @Test
    void listIsTheCountedTermsOfTheTaxonomy() {
        var counted = new TermRepository.Counted(1, "Travel", "travel", null, "Trips abroad", 1);
        when(terms.byTaxonomyCounted("category")).thenReturn(List.of(counted));
        assertThat(resource.list("category")).containsExactly(counted);
    }

    @Test
    void createAnswers201WithTheTermView() {
        when(service.createTerm("category", "Europe", 1L, EDITOR)).thenReturn(term(2, "Europe", "europe", 1L));
        try (var r = resource.create(new TermsResource.NewTerm("category", "Europe", 1L))) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity()).isEqualTo(new TermsResource.TermView(2, "Europe", "europe", 1L, "d"));
        }
    }

    @Test
    void createWithoutABodyIs400() {
        assertThat(thrownBy(() -> resource.create(null)).status).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void updateReturnsTheSavedTermView() {
        var change = new TermService.Change("Trips", "trips", null, "d");
        when(service.updateTerm(1, change, EDITOR)).thenReturn(term(1, "Trips", "trips", null));
        assertThat(resource.update(1, change)).isEqualTo(new TermsResource.TermView(1, "Trips", "trips", null, "d"));
    }

    @Test
    void deleteAnswers204() {
        try (var r = resource.delete(1)) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(service).deleteTerm(1, EDITOR);
    }

    @Test
    void aRefusalIsItsStatusWithErrorAndFieldOnly() {
        try (var r = resource.refused(new ItemService.Refused(400, "invalid", "name", List.of("ignored")))) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "name"));
        }
        try (var r = resource.refused(new ItemService.Refused(409, "default_category", null))) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "default_category"));
        }
    }

    private static ItemService.Refused thrownBy(Runnable call) {
        return (ItemService.Refused) assertThatThrownBy(call::run).isInstanceOf(ItemService.Refused.class).actual();
    }
}
