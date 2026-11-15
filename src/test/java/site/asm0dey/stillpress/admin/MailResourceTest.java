package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.Fixtures.author;
import static site.asm0dey.stillpress.TestIdentities.user;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.mail.Mail;
import site.asm0dey.stillpress.mail.MailFailedException;
import site.asm0dey.stillpress.mail.MailTexts;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@ExtendWith(MockitoExtension.class)
class MailResourceTest {
    @Mock
    SettingRepository settings;
    @Mock
    Secrets secrets;
    @Mock
    SiteMail mail;
    @Mock
    AuthorRepository users;
    MailResource resource;

    @BeforeEach
    void setUp() {
        resource = new MailResource(settings, secrets, mail, users, user(1, "admin", Role.ADMINISTRATOR));
    }

    private static MailResource.Update update(Integer port, String security, String from) {
        return new MailResource.Update("smtp.example.org", port, security, "", from, null);
    }

    private void invalid(MailResource.Update body, String field) {
        try (var r = resource.put(body)) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", field));
        }
    }

    @Test
    void getOnAFreshStoreHasTheDefaultsAndNoPassword() {
        assertThat(resource.get()).isEqualTo(new MailResource.Settings("", 587, "starttls", "", "", false, null));
    }

    @Test
    void getShowsTheSettingsWhetherAPasswordIsSetAndTheLastErrorButNotThePassword() {
        when(settings.get("smtp_host")).thenReturn(Optional.of("smtp.example.org"));
        when(settings.get("smtp_port")).thenReturn(Optional.of("465"));
        when(settings.get("smtp_security")).thenReturn(Optional.of("tls"));
        when(settings.get("smtp_username")).thenReturn(Optional.of("blog"));
        when(settings.get("smtp_from")).thenReturn(Optional.of("blog@example.org"));
        when(settings.get(SiteMail.LAST_ERROR)).thenReturn(Optional.of("2026-11-14T00:00:00Z refused"));
        when(secrets.get(SiteMail.PASSWORD)).thenReturn(Optional.of("s3cret"));
        assertThat(resource.get())
            .isEqualTo(
                    new MailResource.Settings(
                            "smtp.example.org",
                            465,
                            "tls",
                            "blog",
                            "blog@example.org",
                            true,
                            "2026-11-14T00:00:00Z refused"
                    )
            );
    }

    @Test
    void invalidFieldsAre400OnTheFirstBadFieldInFormOrder() {
        invalid(null, "host");
        invalid(new MailResource.Update(null, 25, "none", "", "a@example.org", null), "host");
        invalid(update(null, "none", "blog@example.org"), "port");
        invalid(update(0, "none", "blog@example.org"), "port");
        invalid(update(65536, "none", "blog@example.org"), "port");
        invalid(update(25, "ssl", "blog@example.org"), "security");
        invalid(update(25, "none", "nobody"), "from");
        verifyNoInteractions(settings, secrets, mail);
    }

    @Test
    void aMissingFromIsInvalidToo() {
        invalid(update(25, "none", null), "from");
    }

    @Test
    void testMailNeedsTheAdminsEmail() {
        when(users.findById(1L))
            .thenReturn(Optional.of(new Author(1L, "admin", "A", " ", "admin", null, "administrator", null, null, null))
            );
        try (var r = resource.test()) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "no_email"));
        }
        verify(mail, never()).send(any());
    }

    @Test
    void testMailWithoutMailConfiguredIs409() {
        when(users.findById(1L)).thenReturn(Optional.of(author(1, "admin", Role.ADMINISTRATOR, null)));
        when(mail.configured()).thenReturn(false);
        try (var r = resource.test()) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "mail_not_configured"));
        }
        verify(mail, never()).send(any());
    }

    @Test
    void testMailGoesToTheAdminAndClearsTheLastError() {
        when(users.findById(1L)).thenReturn(Optional.of(author(1, "admin", Role.ADMINISTRATOR, null)));
        when(mail.configured()).thenReturn(true);
        when(settings.get("title")).thenReturn(Optional.of("Blog"));
        var texts = org.mockito.Mockito.mock(MailTexts.class);
        when(texts.testSubject("Blog")).thenReturn("Test email from Blog");
        when(texts.testBody("Blog")).thenReturn("body");
        try (var statics = mockStatic(SiteMail.class)) {
            statics.when(() -> SiteMail.texts("en")).thenReturn(texts);
            try (var r = resource.test()) {
                assertThat(r.getStatus()).isEqualTo(204);
            }
        }
        verify(mail).send(new Mail("admin@example.org", "Test email from Blog", "body"));
        verify(settings).delete(SiteMail.LAST_ERROR);
    }

    @Test
    void testMailFailureIs502WithTheReasonAndKeepsTheLastError() {
        when(users.findById(1L)).thenReturn(Optional.of(author(1, "admin", Role.ADMINISTRATOR, null)));
        when(mail.configured()).thenReturn(true);
        var texts = org.mockito.Mockito.mock(MailTexts.class);
        doThrow(new MailFailedException("connection refused")).when(mail).send(any());
        try (var statics = mockStatic(SiteMail.class)) {
            statics.when(() -> SiteMail.texts("en")).thenReturn(texts);
            try (var r = resource.test()) {
                assertThat(r.getStatus()).isEqualTo(502);
                assertThat(r.getEntity()).isEqualTo(Map.of("error", "mail_failed", "message", "connection refused"));
            }
        }
        verify(settings, never()).delete(SiteMail.LAST_ERROR);
    }
}
