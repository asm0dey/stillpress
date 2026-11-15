package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.TestIdentities.user;
import module java.base;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.Fixtures;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.publish.views.ItemView;
import site.asm0dey.stillpress.publish.views.SiteView;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RevisionRepository;

@ExtendWith(MockitoExtension.class)
class PreviewResourceTest {
    private static final long USER = 7;
    @Mock
    ItemRepository items;
    @Mock
    RevisionRepository revisions;
    @Mock
    ViewFactory views;
    @Mock
    Engine engine;

    private PreviewResource as(SecurityIdentity identity) {
        return new PreviewResource(items, revisions, views, engine, identity);
    }

    @Test
    void unknownItemIs404() {
        when(items.find(9)).thenReturn(Optional.empty());
        try (var r = as(user(USER, "a", Role.AUTHOR)).preview(9)) {
            assertThat(r.getStatus()).isEqualTo(404);
        }
        verifyNoInteractions(revisions, views, engine);
    }

    @Test
    void anAuthorMayNotPreviewSomeoneElsesItem() {
        when(items.find(9)).thenReturn(Optional.of(Fixtures.item(9, "post", "draft", USER + 1)));
        try (var r = as(user(USER, "a", Role.AUTHOR)).preview(9)) {
            assertThat(r.getStatus()).isEqualTo(403);
        }
        verify(revisions, never()).overlayAutosave(any(), org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(views, engine);
    }

    @Test
    void anEditorMayPreviewAnyItem() {
        when(items.find(9)).thenReturn(Optional.of(Fixtures.item(9, "post", "draft", USER + 1)));
        var shown = rendersOverlay(9, "<p>x</p>");
        try (var r = as(user(USER, "e", Role.EDITOR)).preview(9)) {
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getEntity()).isEqualTo("<p>x</p>");
        }
        verify(views).item(shown);
    }

    @Test
    void ownItemIsRenderedFromTheAutosaveOverlayAndNeverCached() {
        when(items.find(9)).thenReturn(Optional.of(Fixtures.item(9, "post", "draft", USER)));
        rendersOverlay(9, "<p>draft</p>");
        try (var r = as(user(USER, "a", Role.AUTHOR)).preview(9)) {
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getHeaderString("Cache-Control")).isEqualTo("no-store");
            assertThat(r.getHeaderString("X-Robots-Tag")).isEqualTo("noindex");
            assertThat(r.getEntity()).isEqualTo("<p>draft</p>");
        }
    }

    /**
     * The overlaid item the repository hands back is the one the template must show.
     */
    private site.asm0dey.stillpress.db.tables.pojos.Item rendersOverlay(long id, String html) {
        var stored = items.find(id).orElseThrow();
        var overlaid = Fixtures.item(id, "post", "draft", stored.authorId());
        var view = mock(ItemView.class);
        var site = new SiteView("t", "d", "en", "http://h");
        when(revisions.overlayAutosave(stored, USER)).thenReturn(overlaid);
        when(views.site()).thenReturn(site);
        when(views.item(overlaid)).thenReturn(view);
        var template = mock(Template.class);
        var instance = mock(TemplateInstance.class, RETURNS_SELF);
        when(engine.getTemplate("plain/single")).thenReturn(template);
        when(template.data("site", site)).thenReturn(instance);
        when(instance.data("item", view)).thenReturn(instance);
        when(instance.render()).thenReturn(html);
        return overlaid;
    }
}
