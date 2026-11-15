package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.TestIdentities.user;
import module java.base;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.MediaService;
import site.asm0dey.stillpress.db.tables.pojos.Media;
import site.asm0dey.stillpress.store.MediaRepository;
import site.asm0dey.stillpress.wpapi.OEmbedProxy;

@ExtendWith(MockitoExtension.class)
class MediaResourceTest {
    private static final long USER = 7;
    @Mock
    MediaRepository media;
    @Mock
    OEmbedProxy oembed;
    @Mock
    MediaService service;

    private MediaResource as(Role role) {
        return as(user(USER, "u", role));
    }

    private MediaResource as(SecurityIdentity identity) {
        return new MediaResource(media, oembed, service, identity);
    }

    private static Media row(long id) {
        return new Media(
                id,
                null,
                null,
                "/media/2024/01/a.jpg",
                "image/jpeg",
                null,
                "an a",
                "A",
                640,
                480,
                3L,
                null,
                USER
        );
    }

    private static final MediaResource.MediaView VIEW =
            new MediaResource.MediaView(5, "/media/2024/01/a.jpg", "image/jpeg", 640, 480, "A", "an a");

    @Test
    void mediaByIdIsTheViewOfTheRow() {
        when(media.findById(5)).thenReturn(Optional.of(row(5)));
        assertThat(as(Role.CONTRIBUTOR).get(5)).isEqualTo(VIEW);
    }

    @Test
    void unknownMediaIs404WithTheErrorBody() {
        when(media.findById(6)).thenReturn(Optional.empty());
        var resource = as(Role.CONTRIBUTOR);
        var thrown = catchRefused(() -> resource.get(6));
        try (var r = resource.refused(thrown)) {
            assertThat(r.getStatus()).isEqualTo(404);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "not_found"));
        }
    }

    private static ItemService.Refused catchRefused(Runnable call) {
        return (ItemService.Refused) assertThatThrownBy(call::run).isInstanceOf(ItemService.Refused.class).actual();
    }

    @Test
    void listClampsThePageSizeAndMapsEachRow() {
        when(media.list("image", "a", 2, 1)).thenReturn(new MediaRepository.Page(List.of(row(5)), 9));
        when(media.list(null, null, 1, 100)).thenReturn(new MediaRepository.Page(List.of(), 0));
        assertThat(as(Role.AUTHOR).list("image", "a", 2, 0)).isEqualTo(new MediaResource.Library(List.of(VIEW), 9));
        assertThat(as(Role.AUTHOR).list(null, null, 1, 5000).total()).isZero();
    }

    @Test
    void contributorCannotUploadOrEditMeta() {
        var resource = as(Role.CONTRIBUTOR);
        var file = mock(FileUpload.class);
        var upload = catchRefused(() -> resource.upload(file));
        var update = catchRefused(() -> resource.update(5, new MediaResource.Meta("T", "a")));
        assertThat(upload.status).isEqualTo(403);
        assertThat(update.status).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void uploadWithoutAFileIs400() {
        try (var r = as(Role.AUTHOR).upload(null)) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "file is required"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void uploadStoresTheFileForTheActor() {
        var file = mock(FileUpload.class);
        var path = Path.of("/tmp/upload");
        when(file.fileName()).thenReturn("My Photo.PNG");
        when(file.uploadedFile()).thenReturn(path);
        when(service.store("My Photo.PNG", path, USER)).thenReturn(row(5));
        try (var r = as(Role.AUTHOR).upload(file)) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity()).isEqualTo(VIEW);
        }
    }

    @Test
    void metaUpdateWithoutBodyIs400() {
        var resource = as(Role.AUTHOR);
        var thrown = catchRefused(() -> resource.update(5, null));
        assertThat(thrown.status).isEqualTo(400);
        assertThat(thrown.error).isEqualTo("invalid");
        verifyNoInteractions(service);
    }

    @Test
    void metaUpdateReturnsTheSavedRow() {
        when(service.updateMeta(5, "T", "an a")).thenReturn(row(5));
        assertThat(as(Role.AUTHOR).update(5, new MediaResource.Meta("T", "an a"))).isEqualTo(VIEW);
    }

    @Test
    void metaUpdateOfAnUnknownIdPropagatesTheServicesRefusal() {
        when(service.updateMeta(anyLong(), anyString(), anyString()))
            .thenThrow(new ItemService.Refused(404, "not_found", null));
        var resource = as(Role.AUTHOR);
        assertThat(catchRefused(() -> resource.update(6, new MediaResource.Meta("T", "a"))).status).isEqualTo(404);
    }

    @Test
    void aRefusedUploadTypeIs400WithItsCodeAsTheError() {
        try (var r = as(Role.AUTHOR).notAllowed(new IllegalArgumentException("type_not_allowed"))) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "type_not_allowed"));
        }
    }

    @Test
    void oembedIsTheProxysAnswerForAnyRole() {
        var answer = Response.status(404).entity(Map.of("code", "oembed_invalid_url")).build();
        when(oembed.fetch("http://169.254.169.254/")).thenReturn(answer);
        assertThat(as(Role.CONTRIBUTOR).oembed("http://169.254.169.254/")).isSameAs(answer);
        verify(oembed).fetch("http://169.254.169.254/");
    }
}
