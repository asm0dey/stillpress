package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.Fixtures.author;
import static site.asm0dey.stillpress.TestIdentities.user;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SetPasswordLink;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.mail.Mail;
import site.asm0dey.stillpress.mail.MailTexts;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TakenFixtures;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@ExtendWith(MockitoExtension.class)
class UsersResourceTest {
    @Mock
    AuthorRepository users;
    @Mock
    SetPasswordLink links;
    @Mock
    ImportGuard guard;
    @Mock
    ItemRepository items;
    @Mock
    FragmentRepository fragments;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    SiteMail mail;
    @Mock
    SettingRepository settings;
    @Mock
    TwoFactorRepository factors;
    @Mock
    SessionRepository sessions;

    private UsersResource as(long id, Role role) {
        return new UsersResource(
                users,
                links,
                guard,
                items,
                fragments,
                queue,
                jobs,
                user(id, "caller", role),
                mail,
                settings,
                factors,
                sessions
        );
    }

    UsersResource admin;

    @BeforeEach
    void setUp() {
        admin = as(1, Role.ADMINISTRATOR);
    }

    private static UsersResource.NewUser newUser(String login, String email, String name, String role) {
        return new UsersResource.NewUser(login, email, name, role);
    }

    private static void assertAnswer(Response r, int status, Map<String, String> body) {
        try (r) {
            assertThat(r.getStatus()).isEqualTo(status);
            assertThat(r.getEntity()).isEqualTo(body);
        }
    }

    private static void assertNoContent(Response r) {
        try (r) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
    }

    private static Author writer() {
        return author(5, "writer", Role.AUTHOR, "hash");
    }

    @Test
    void listIsSortedByLoginWithRolePasswordAndTwoFactor() {
        when(users.allWithTwoFactor())
            .thenReturn(List.of(
                    new AuthorRepository.WithTwoFactor(writer(), true),
                    new AuthorRepository.WithTwoFactor(author(1, "admin", Role.ADMINISTRATOR, null), false)
            ));
        assertThat(admin.list())
            .containsExactly(
                    new UsersResource.UserView(1, "admin", "admin", "admin@example.org", "administrator", false, false),
                    new UsersResource.UserView(5, "writer", "writer", "writer@example.org", "author", true, true)
            );
    }

    @Test
    void clearTwoFactorRemovesEveryFactorAndSignsTheUserOutEverywhere() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        assertNoContent(admin.clearTwoFactor(5));
        verify(factors).clear(5);
        verify(sessions).deleteAllFor(5);
    }

    @Test
    void clearTwoFactorOfAnUnknownUserIs404() {
        when(users.findById(999999)).thenReturn(Optional.empty());
        assertAnswer(admin.clearTwoFactor(999999), 404, Map.of("error", "not_found"));
        verifyNoInteractions(factors, sessions);
    }

    @Test
    void createWithoutMailAnswers201WithAManualSetPasswordLinkAndRepublishesTheAuthorArchive() {
        var created = author(9, "editor1", Role.EDITOR, null);
        when(users.findByLogin("editor1")).thenReturn(Optional.empty());
        when(users.emailTaken("editor1@example.org", -1)).thenReturn(false);
        when(users.create("editor1", "editor1@example.org", "E", Role.EDITOR)).thenReturn(created);
        when(mail.configured()).thenReturn(false);
        when(links.issue(9, TokenRepository.SET_PASSWORD, SetPasswordLink.MANUAL_TTL))
            .thenReturn("/admin/set-password?token=t");
        try (var r = admin.create(newUser("editor1", "editor1@example.org", "E", "editor"))) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity()).isEqualTo(Map.of(
                    "id",
                    9L,
                    "mailed",
                    false,
                    "setPasswordUrl",
                    "/admin/set-password?token=t"
            ));
        }
        verify(guard).close();
        verify(queue).base("/author/editor1/");
        verify(jobs).requestPublish();
    }

    @Test
    void createWithMailAnswers201MailedAndSendsTheInviteAfterCommit() {
        var created = author(9, "editor1", Role.EDITOR, null);
        when(users.findByLogin("editor1")).thenReturn(Optional.empty());
        when(users.create("editor1", "editor1@example.org", "E", Role.EDITOR)).thenReturn(created);
        when(mail.configured()).thenReturn(true);
        when(settings.get("title")).thenReturn(Optional.of("Blog"));
        when(links.issue(9, TokenRepository.INVITE, SetPasswordLink.INVITE_TTL)).thenReturn("https://blog/set?token=t");
        var texts = mock(MailTexts.class);
        when(texts.inviteSubject("Blog")).thenReturn("You are invited to Blog");
        when(texts.inviteBody("editor1", "Blog", "https://blog/set?token=t", 7)).thenReturn("body");
        try (var statics = mockStatic(SiteMail.class)) {
            statics.when(() -> SiteMail.texts("en")).thenReturn(texts);
            try (var r = admin.create(newUser("editor1", "editor1@example.org", "E", "editor"))) {
                assertThat(r.getStatus()).isEqualTo(201);
                assertThat(r.getEntity()).isEqualTo(Map.of("id", 9L, "mailed", true));
            }
        }
        verify(mail).sendAfterCommit(new Mail("editor1@example.org", "You are invited to Blog", "body"));
    }

    @Test
    void createTrimsLoginEmailAndDisplayName() {
        when(users.findByLogin("e1")).thenReturn(Optional.empty());
        when(users.create("e1", "e1@example.org", "E", Role.EDITOR)).thenReturn(author(9, "e1", Role.EDITOR, null));
        when(links.issue(9, TokenRepository.SET_PASSWORD, SetPasswordLink.MANUAL_TTL)).thenReturn("link");
        admin.create(newUser(" e1 ", " e1@example.org ", " E ", "editor")).close();
        verify(users).create("e1", "e1@example.org", "E", Role.EDITOR);
    }

    @Test
    void createWithABadFieldIs400OnItAndChangesNothing() {
        var cases = new LinkedHashMap<String, UsersResource.NewUser>();
        cases.put("login", newUser("x".repeat(61), "a@example.org", "A", "author"));
        cases.put("login ", newUser("bad/login", "a@example.org", "A", "author"));
        cases.put("login  ", null);
        cases.put("email", newUser("a", "x".repeat(95) + "@e.org", "A", "author"));
        cases.put("displayName", newUser("a", "a@example.org", "x".repeat(251), "author"));
        cases.forEach((label, body) -> {
            try (var r = admin.create(body)) {
                assertThat(r.getStatus()).as(label).isEqualTo(400);
                assertThat(r.getEntity()).as(label).isEqualTo(Map.of("error", "invalid", "field", label.strip()));
            }
        });
        verifyNoInteractions(guard, queue, jobs);
        verify(users, never()).create(any(), any(), any(), any());
    }

    @Test
    void createWithAnUnknownRoleIs400InvalidRole() {
        assertAnswer(admin.create(newUser("a", "a@example.org", "A", "king")), 400, Map.of("error", "invalid_role"));
        verify(users, never()).create(any(), any(), any(), any());
    }

    @Test
    void createWithATakenLoginOrEmailIs409AndLeavesImportOpen() {
        when(users.findByLogin("writer")).thenReturn(Optional.of(writer()));
        assertAnswer(
                admin.create(newUser("writer", "w@example.org", "W", "author")),
                409,
                Map.of("error", "login_taken")
        );
        when(users.findByLogin("other")).thenReturn(Optional.empty());
        when(users.emailTaken("WRITER@example.org", -1)).thenReturn(true);
        assertAnswer(
                admin.create(newUser("other", "WRITER@example.org", "O", "author")),
                409,
                Map.of("error", "email_taken")
        );
        verifyNoInteractions(guard);
    }

    @Test
    void aUniqueIndexRefusalFromAConcurrentCreateIs409ThrownSoTheTransactionRollsBack() {
        when(users.findByLogin("dup")).thenReturn(Optional.empty());
        when(users.create("dup", "dup@example.org", "D", Role.AUTHOR)).thenThrow(TakenFixtures.login());
        assertThatThrownBy(() -> admin.create(newUser("dup", "dup@example.org", "D", "author")))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(409);
                assertThat(e.getResponse().getEntity()).isEqualTo(Map.of("error", "login_taken"));
            });
        when(users.create("mail", "a@x.org", "D", Role.AUTHOR)).thenThrow(TakenFixtures.email());
        assertThatThrownBy(() -> admin.create(newUser("mail", "a@x.org", "D", "author")))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> assertThat(e.getResponse().getEntity())
                .isEqualTo(Map.of("error", "email_taken")));
        verifyNoInteractions(queue, jobs);
    }

    @Test
    void inviteOfAnUnknownUserIs404() {
        when(users.findById(999999)).thenReturn(Optional.empty());
        assertAnswer(admin.sendInvite(999999), 404, Map.of("error", "not_found"));
    }

    @Test
    void inviteWithoutMailConfiguredIs409() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        when(mail.configured()).thenReturn(false);
        assertAnswer(admin.sendInvite(5), 409, Map.of("error", "mail_not_configured"));
    }

    @Test
    void inviteOfAUserWithoutAnEmailIs400NoEmail() {
        when(users.findById(6))
            .thenReturn(Optional.of(new Author(6L, "noemail", "N", null, "noemail", null, "author", null, null, null)));
        when(mail.configured()).thenReturn(true);
        assertAnswer(admin.sendInvite(6), 400, Map.of("error", "no_email"));
        verify(mail, never()).sendAfterCommit(any());
        verifyNoInteractions(guard);
    }

    @Test
    void inviteMailsTheUserTheirOwnLinkAndAnswers204() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        when(mail.configured()).thenReturn(true);
        when(links.issue(5, TokenRepository.INVITE, SetPasswordLink.INVITE_TTL)).thenReturn("link");
        var texts = mock(MailTexts.class);
        when(texts.inviteSubject("Stillpress")).thenReturn("subject");
        when(texts.inviteBody("writer", "Stillpress", "link", 7)).thenReturn("body");
        try (var statics = mockStatic(SiteMail.class)) {
            statics.when(() -> SiteMail.texts("en")).thenReturn(texts);
            assertNoContent(admin.sendInvite(5));
        }
        verify(guard).close();
        verify(mail).sendAfterCommit(new Mail("writer@example.org", "subject", "body"));
    }

    @Test
    void aMailGreetsByDisplayNameOrByLoginWhenTheImportedAuthorHasNone() {
        assertThat(UsersResource.displayName(
                new Author(1L, "login", "Name", null, "s", null, "author", null, null, null)
        ))
            .isEqualTo("Name");
        assertThat(UsersResource.displayName(new Author(1L, "login", " ", null, "s", null, "author", null, null, null)))
            .isEqualTo("login");
        assertThat(UsersResource.displayName(new Author(1L, "login", null, null, "s", null, "author", null, null, null))
        )
            .isEqualTo("login");
    }

    @Test
    void anAuthorEditingSomeoneElseIs403AndNothingIsLookedUp() {
        var self = as(5, Role.AUTHOR);
        assertAnswer(
                self.update(1, new UsersResource.Profile("Hacked", "admin@example.org")),
                403,
                Map.of("error", "forbidden")
        );
        verifyNoInteractions(users, guard);
    }

    @Test
    void updateOfAnUnknownUserIs404() {
        when(users.findById(999999)).thenReturn(Optional.empty());
        assertAnswer(
                admin.update(999999, new UsersResource.Profile("W", "x@example.org")),
                404,
                Map.of("error", "not_found")
        );
    }

    @Test
    void updateWithABadFieldIs400AndLeavesImportOpen() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        assertAnswer(
                admin.update(5, new UsersResource.Profile("x".repeat(251), "w@example.org")),
                400,
                Map.of("error", "invalid", "field", "displayName")
        );
        assertAnswer(
                admin.update(5, new UsersResource.Profile("W", "x".repeat(95) + "@e.org")),
                400,
                Map.of("error", "invalid", "field", "email")
        );
        assertAnswer(admin.update(5, null), 400, Map.of("error", "invalid", "field", "displayName"));
        verifyNoInteractions(guard);
        verify(users, never()).setProfile(anyLong(), any(), any());
    }

    @Test
    void updateToAnEmailAnotherUserHasIs409() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        when(users.emailTaken("ADMIN@example.org", 5)).thenReturn(true);
        assertAnswer(
                as(5, Role.AUTHOR).update(5, new UsersResource.Profile("W", "ADMIN@example.org")),
                409,
                Map.of("error", "email_taken")
        );
        verifyNoInteractions(guard);
    }

    @Test
    void aConcurrentChangeTakingTheEmailIs409ThrownSoTheTransactionRollsBack() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        org.mockito.Mockito.doThrow(TakenFixtures.email()).when(users).setProfile(5, "P", "same@x.org");
        assertThatThrownBy(() -> admin.update(5, new UsersResource.Profile("P", "same@x.org")))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(409);
                assertThat(e.getResponse().getEntity()).isEqualTo(Map.of("error", "email_taken"));
            });
        verifyNoInteractions(queue, jobs);
    }

    @Test
    void aNewDisplayNameIsSavedTrimmedAndRepublishesTheArchiveAndTheUsersPublishedItems() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        when(items.publishedIdsBy(5)).thenReturn(List.of(11L, 12L));
        assertNoContent(as(5, Role.AUTHOR)
            .update(5, new UsersResource.Profile("  Wendy Writer ", " wendy@example.org "))
        );
        verify(guard).close();
        verify(users).setProfile(5, "Wendy Writer", "wendy@example.org");
        verify(fragments).delete(List.of(11L, 12L));
        verify(queue).items(List.of(11L, 12L));
        verify(queue).base("/author/writer/");
        verify(jobs).requestPublish();
    }

    @Test
    void anUnchangedDisplayNameRepublishesNothing() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        assertNoContent(admin.update(5, new UsersResource.Profile("writer", "new@example.org")));
        verify(users).setProfile(5, "writer", "new@example.org");
        verifyNoInteractions(queue, jobs, fragments);
    }

    @Test
    void anAdministratorEditsAnyone() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        assertNoContent(admin.update(5, new UsersResource.Profile("writer", "w2@example.org")));
        verify(users).setProfile(5, "writer", "w2@example.org");
    }

    @Test
    void aRoleChangeIs204AndClosesImport() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        when(users.setRoleKeepingAdministrator(5, Role.EDITOR)).thenReturn(true);
        assertNoContent(admin.setRole(5, new UsersResource.RoleChange("editor")));
        verify(guard).close();
    }

    @Test
    void anUnknownOrMissingRoleIs400InvalidRole() {
        assertAnswer(admin.setRole(5, new UsersResource.RoleChange("king")), 400, Map.of("error", "invalid_role"));
        assertAnswer(admin.setRole(5, new UsersResource.RoleChange(null)), 400, Map.of("error", "invalid_role"));
        assertAnswer(admin.setRole(5, null), 400, Map.of("error", "invalid_role"));
        verifyNoInteractions(users);
    }

    @Test
    void aRoleChangeOfAnUnknownUserIs404() {
        when(users.findById(999999)).thenReturn(Optional.empty());
        assertAnswer(admin.setRole(999999, new UsersResource.RoleChange("editor")), 404, Map.of("error", "not_found"));
        verify(users, never()).setRoleKeepingAdministrator(anyLong(), any());
    }

    @Test
    void demotingTheLastAdministratorIs409AndLeavesImportOpen() {
        when(users.findById(1)).thenReturn(Optional.of(author(1, "admin", Role.ADMINISTRATOR, "hash")));
        when(users.setRoleKeepingAdministrator(1, Role.EDITOR)).thenReturn(false);
        assertAnswer(
                admin.setRole(1, new UsersResource.RoleChange("editor")),
                409,
                Map.of("error", "last_administrator")
        );
        verifyNoInteractions(guard);
    }

    @Test
    void passwordLinkIsIssuedForAnExistingUserAndClosesImport() {
        when(users.findById(5)).thenReturn(Optional.of(writer()));
        when(links.issue(5, TokenRepository.SET_PASSWORD, SetPasswordLink.MANUAL_TTL))
            .thenReturn("/admin/set-password?token=t");
        assertAnswer(admin.passwordLink(5), 200, Map.of("url", "/admin/set-password?token=t"));
        verify(guard).close();
    }

    @Test
    void passwordLinkForAnUnknownUserIs404() {
        when(users.findById(999999)).thenReturn(Optional.empty());
        assertAnswer(admin.passwordLink(999999), 404, Map.of("error", "not_found"));
        verifyNoInteractions(links, guard);
    }
}
