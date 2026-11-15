package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import site.asm0dey.stillpress.Fixtures;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RedirectRepository;

@ExtendWith(MockitoExtension.class)
class RedirectsResourceTest {
    private static final String POST = "/2023/01/14/post/";
    @Mock
    RedirectRepository redirects;
    @Mock
    ItemRepository items;
    @Mock
    ImportGuard guard;
    RedirectsResource resource;

    @BeforeEach
    void setUp() {
        resource = new RedirectsResource(redirects, items, guard);
    }

    private static RedirectsResource.NewRedirect redirect(String from, String to, Integer status) {
        return new RedirectsResource.NewRedirect(from, to, status);
    }

    private void refused(RedirectsResource.NewRedirect n, String field) {
        try (var r = resource.add(n)) {
            assertThat(r.getStatus()).as(n.toString()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", field));
        }
    }

    @Test
    void invalidFieldsAre400AndNothingIsWritten() {
        refused(redirect("old-page/", "/x/", 301), "from");
        refused(redirect("/a/../b/", "/x/", 301), "from");
        refused(redirect("/a\u0001/", "/x/", 301), "from");
        refused(redirect(null, "/x/", 301), "from");
        refused(redirect("/a/", "javascript:alert(1)", 301), "to");
        refused(redirect("/a/", "//evil.example/", 301), "to");
        refused(redirect("/a/", "/a/", 301), "to");
        refused(redirect("/a/", "/x/", 307), "status");
        refused(redirect("/a/", "/x/", null), "status");
        try (var r = resource.add(null)) {
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "from"));
        }
        verifyNoInteractions(redirects, guard, items);
    }

    @Test
    void validRedirectIsStoredAndClosesImport() {
        when(items.findByPermalink("/old-page/")).thenReturn(Optional.empty());
        try (var r = resource.add(redirect("/old-page/", POST, 301))) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity()).isEqualTo(new RedirectRepository.Row("/old-page/", POST, 301));
        }
        verify(guard).close();
        verify(redirects).add("/old-page/", POST, 301);
    }

    @Test
    void absoluteTargetAndImporterQueryAreAccepted() {
        when(items.findByPermalink(anyString())).thenReturn(Optional.empty());
        try (var r = resource.add(redirect("/away/", "https://example.org/x", 302))) {
            assertThat(r.getStatus()).isEqualTo(201);
        }
        try (var r = resource.add(redirect("?p=77", "/x/", 301))) {
            assertThat(r.getStatus()).isEqualTo(201);
        }
        verify(redirects).add("?p=77", "/x/", 301);
    }

    @Test
    void cannotShadowAPublishedItem() {
        when(items.findByPermalink(POST)).thenReturn(Optional.of(Fixtures.item(1, "post", "publish", 1L)));
        try (var r = resource.add(redirect(POST, "/elsewhere/", 301))) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "shadows_item", "field", "from"));
        }
        verify(redirects, never()).add(anyString(), anyString(), anyInt());
        verify(guard, never()).close();
    }

    @Test
    void anUnpublishedItemAtThePathDoesNotBlockIt() {
        when(items.findByPermalink(POST)).thenReturn(Optional.of(Fixtures.item(1, "post", "draft", 1L)));
        try (var r = resource.add(redirect(POST, "/elsewhere/", 301))) {
            assertThat(r.getStatus()).isEqualTo(201);
        }
    }

    @Test
    void deleteRemovesAnExistingRedirect() {
        when(redirects.find("/old-page/")).thenReturn(Optional.of(new RedirectRepository.Row("/old-page/", "/n/", 301)));
        try (var r = resource.delete("/old-page/")) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(guard).close();
        verify(redirects).delete("/old-page/");
    }

    @Test
    void deleteOfAnUnknownOrMissingKeyIs404() {
        when(redirects.find("/none/")).thenReturn(Optional.empty());
        for (var from : new String[] {"/none/", null}) {
            try (var r = resource.delete(from)) {
                assertThat(r.getStatus()).isEqualTo(404);
                assertThat(r.getEntity()).isEqualTo(Map.of("error", "not_found"));
            }
        }
        verify(redirects, never()).delete(any());
    }

    @Test
    void listPagesFiftyAtATimeAndClampsPageToOne() {
        var row = new RedirectRepository.Row("/a/", "/b/", 301);
        when(redirects.list("old", 100, 50)).thenReturn(List.of(row));
        when(redirects.list("old", 0, 50)).thenReturn(List.of());
        when(redirects.count("old")).thenReturn(1);
        assertThat(resource.list("old", 3)).isEqualTo(new RedirectsResource.RedirectList(List.of(row), 1));
        assertThat(resource.list("old", -4).items()).isEmpty();
    }
}
