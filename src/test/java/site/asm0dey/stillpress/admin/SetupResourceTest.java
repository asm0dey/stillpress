package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.Fixtures.author;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Captcha;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Everything the wizard decides before its transaction opens. The committed path (settings, user, session, publish)
 * is in {@link SetupResourceQuarkusTest}.
 */
@ExtendWith(MockitoExtension.class)
class SetupResourceTest {
    @Mock
    SetupToken token;
    @Mock
    Captcha captcha;
    @Mock
    AuthorRepository users;
    @Mock
    SessionRepository sessions;
    @Mock
    SettingRepository settings;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    ImportGuard guard;
    SetupResource resource;

    @BeforeEach
    void setUp() {
        resource = new SetupResource(token, captcha, users, sessions, settings, queue, jobs, guard);
        resource.request = mock(HttpServerRequest.class);
        var peer = mock(SocketAddress.class);
        lenient().when(peer.hostAddress()).thenReturn("198.51.100.7");
        lenient().when(resource.request.remoteAddress()).thenReturn(peer);
    }

    private static SetupResource.Setup body(String field, String value) {
        var m = new HashMap<String, String>(Map.of(
                "token",
                "tok",
                "title",
                "My Site",
                "language",
                "en-GB",
                "login",
                "admin0",
                "email",
                "admin0@example.org",
                "displayName",
                "Admin",
                "password",
                "correct horse battery"
        ));
        if (field != null) {
            m.put(field, value);
        }
        return new SetupResource.Setup(
                m.get("token"),
                m.get("title"),
                m.get("language"),
                m.get("login"),
                m.get("email"),
                m.get("displayName"),
                m.get("password"),
                m.get("timezone"),
                "solved",
                "hp"
        );
    }

    private void tokenOk() {
        when(token.matches("tok")).thenReturn(true);
    }

    private void assertAnswer(Response r, int status, Map<String, String> body) {
        try (r) {
            assertThat(r.getStatus()).isEqualTo(status);
            assertThat(r.getEntity()).isEqualTo(body);
        }
    }

    @Test
    void statusSaysRequiredUntilAnAdministratorHasAPassword() {
        when(users.anyAdministratorWithPassword()).thenReturn(false, true);
        assertThat(resource.status()).isEqualTo(Map.of("required", true));
        assertThat(resource.status()).isEqualTo(Map.of("required", false));
    }

    @Test
    void secondSetupIs409EvenWithTheTokenAndChecksNothingElse() {
        when(users.anyAdministratorWithPassword()).thenReturn(true);
        assertAnswer(resource.setup(body(null, null)), 409, Map.of("error", "already_set_up"));
        verifyNoInteractions(token, captcha);
    }

    @Test
    void aWrongOrMissingBodyOrTokenIs403BadToken() {
        when(token.matches("nope")).thenReturn(false);
        assertAnswer(resource.setup(body("token", "nope")), 403, Map.of("error", "bad_token"));
        assertAnswer(resource.setup(null), 403, Map.of("error", "bad_token"));
        verifyNoInteractions(captcha, sessions);
        verify(token, never()).discard();
    }

    @Test
    void theCaptchaIsVerifiedWithTheTokenTheHoneypotAndTheClientAddress() {
        tokenOk();
        resource.setup(body("language", "Russian"));
        verify(captcha).verify("solved", "hp", "198.51.100.7");
    }

    @Test
    void invalidFieldsAre400OnTheFirstBadFieldAndTheTokenSurvives() {
        tokenOk();
        var cases = new LinkedHashMap<String, String>();
        cases.put("title", " ");
        cases.put("language", "Russian");
        cases.put("login", "ad#min");
        cases.put("email", "nobody");
        cases.put("displayName", "x".repeat(251));
        cases.put("password", "short");
        cases.forEach((field, value) -> assertAnswer(
                resource.setup(body(field, value)),
                400,
                Map.of("error", "invalid", "field", field)
        ));
        verify(token, never()).discard();
        verifyNoInteractions(sessions, queue, jobs);
    }

    @Test
    void aBadTimezoneIs400OnTimezone() {
        tokenOk();
        var s = body(null, null);
        var withZone = new SetupResource.Setup(
                s.token(),
                s.title(),
                s.language(),
                s.login(),
                s.email(),
                s.displayName(),
                s.password(),
                "Mars/Base",
                s.captcha(),
                s.website()
        );
        assertAnswer(resource.setup(withZone), 400, Map.of("error", "invalid", "field", "timezone"));
    }

    @Test
    void fieldsAreTrimmedBeforeTheyAreChecked() {
        tokenOk();
        assertAnswer(resource.setup(body("login", "  ad#min ")), 400, Map.of("error", "invalid", "field", "login"));
        // a padded but valid value passes validation and goes on to the email check
        when(users.findByLogin("admin0")).thenReturn(Optional.empty());
        when(users.emailTaken("admin0@example.org", -1L)).thenReturn(true);
        assertAnswer(resource.setup(body("email", " admin0@example.org ")), 409, Map.of("error", "email_taken"));
    }

    @Test
    void anEmailAnotherUserHasIs409EmailTakenButAnUpgradedAuthorsOwnIsNot() {
        tokenOk();
        when(users.findByLogin("admin0")).thenReturn(Optional.of(author(9, "admin0", Role.AUTHOR, null)));
        when(users.emailTaken("admin0@example.org", 9L)).thenReturn(true);
        assertAnswer(resource.setup(body(null, null)), 409, Map.of("error", "email_taken"));
        verify(token, never()).discard();
        verify(users, never()).create(any(), any(), any(), any());
        verify(users, never()).setRole(anyLong(), any());
    }

    @Test
    void aRequestWithoutAHostIs400NoHostAndNothingIsCreated() {
        tokenOk();
        when(users.findByLogin("admin0")).thenReturn(Optional.empty());
        when(resource.request.authority()).thenReturn(null);
        assertAnswer(resource.setup(body(null, null)), 400, Map.of("error", "no_host"));
        verify(token, never()).discard();
        verifyNoInteractions(sessions, queue, jobs);
    }

    @Test
    void startEmptyClosesImportAndAnswers204() {
        try (var r = resource.startEmpty()) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(guard).close();
    }
}
