package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.Fixtures.author;
import static site.asm0dey.stillpress.TestIdentities.user;
import io.vertx.core.http.HttpServerRequest;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.LoginLimiter;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.importer.ImportRunningException;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@ExtendWith(MockitoExtension.class)
class ResetResourceTest {
    private static final String PASSWORD = TestStore.PASSWORD;
    private static final String SESSION = "raw-session";
    @Mock
    SiteReset reset;
    @Mock
    AuthorRepository users;
    @Mock
    SettingRepository settings;
    // the real limiter: the lockout is its behaviour, and the resource must feed it the same buckets as login
    LoginLimiter limiter = new LoginLimiter(Clock.systemUTC());
    ResetResource resource;

    @BeforeEach
    void setUp() {
        resource = new ResetResource(reset, limiter, users, settings, user(1, "admin", Role.ADMINISTRATOR));
        resource.request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        lenient().when(resource.request.remoteAddress().hostAddress()).thenReturn("198.51.100.7");
        lenient().when(settings.get("title")).thenReturn(Optional.of("My Site"));
    }

    private void adminWithPassword() {
        when(users.findById(1L)).thenReturn(Optional.of(author(1, "admin", Role.ADMINISTRATOR, TestStore.PASSWORD_HASH))
        );
    }

    private static ResetResource.Confirmation confirm(String password, String confirm) {
        return new ResetResource.Confirmation(password, confirm);
    }

    @Test
    void rightPasswordAndTitleResetsKeepingTheCallerAndTheirSession() {
        adminWithPassword();
        when(reset.reset(1, SESSION)).thenReturn(true);
        try (var r = resource.reset(confirm(PASSWORD, "My Site"), SESSION)) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(reset).reset(1, SESSION);
    }

    @Test
    void aFailedFileStepIs500Files() {
        adminWithPassword();
        when(reset.reset(1, SESSION)).thenReturn(false);
        try (var r = resource.reset(confirm(PASSWORD, "My Site"), SESSION)) {
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "files"));
        }
    }

    @Test
    void wrongPasswordIs403AndNothingIsReset() {
        adminWithPassword();
        try (var r = resource.reset(confirm("wrong password!", "My Site"), SESSION)) {
            assertThat(r.getStatus()).isEqualTo(403);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid_credentials"));
        }
        verifyNoInteractions(reset);
    }

    @Test
    void aMissingPasswordIs403() {
        try (var r = resource.reset(confirm(null, "My Site"), SESSION)) {
            assertThat(r.getStatus()).isEqualTo(403);
        }
        verifyNoInteractions(reset);
    }

    @Test
    void fifthWrongPasswordLocksOutEvenTheRightOneWith429AndRetryAfter() {
        adminWithPassword();
        for (var i = 0; i < 5; i++) {
            try (var r = resource.reset(confirm("wrong password!", "My Site"), SESSION)) {
                assertThat(r.getStatus()).isEqualTo(403);
            }
        }
        try (var r = resource.reset(confirm(PASSWORD, "My Site"), SESSION)) {
            assertThat(r.getStatus()).isEqualTo(429);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "rate_limited"));
            assertThat(Integer.parseInt(r.getHeaderString("Retry-After"))).isPositive();
        }
        verifyNoInteractions(reset);
    }

    @Test
    void wrongConfirmIs400OnConfirmBeforeAnyPasswordCheck() {
        for (var body : new ResetResource.Confirmation[] {confirm(PASSWORD, "my site"), confirm(PASSWORD, null), null}) {
            try (var r = resource.reset(body, SESSION)) {
                assertThat(r.getStatus()).isEqualTo(400);
                assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "confirm"));
            }
        }
        verifyNoInteractions(reset, users);
    }

    @Test
    void anImportInProgressIs409ImportRunningThroughTheGlobalMapper() {
        try (var r = new ImportRunningMapper().importRunning(new ImportRunningException())) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "import_running"));
        }
    }
}
