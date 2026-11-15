package site.asm0dey.stillpress.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@ExtendWith(MockitoExtension.class)
class ReviewNotificationsTest {
    @Mock
    SiteMail mail;
    @Mock
    AuthorRepository authors;
    @Mock
    SettingRepository settings;
    @Mock
    MailTexts texts;
    ReviewNotifications notifications;

    @BeforeEach
    void setUp() {
        notifications = new ReviewNotifications(mail, authors, settings);
    }

    private static Author author(long id, String login, String email) {
        return new Author(id, login, login, email, login, null, "editor", null, null, null);
    }

    @Test
    void editorSubmittingOwnPostSkipsThemselves() {
        var admin = author(1, "admin", "admin@example.org");
        var editor = author(2, "ed", "ed@example.org");
        when(mail.configured()).thenReturn(true);
        when(authors.findById(2)).thenReturn(Optional.of(editor));
        when(authors.reviewers()).thenReturn(List.of(admin, editor));
        when(texts.reviewSubject("Stillpress", "Mine")).thenReturn("subject");
        when(texts.reviewBody("ed", "Mine", "/admin/edit/42")).thenReturn("body");
        // the message bundle exists only inside the running application
        try (var siteMail = mockStatic(SiteMail.class)) {
            siteMail
                .when(() -> SiteMail.texts("en"))
                .thenReturn(texts);
            notifications.pendingReview(42, "Mine", 2);
        }
        var sent = ArgumentCaptor.forClass(Mail.class);
        verify(mail).sendAfterCommit(sent.capture());
        assertThat(sent.getAllValues()).containsExactly(new Mail("admin@example.org", "subject", "body"));
    }
}
