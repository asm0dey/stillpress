package site.asm0dey.stillpress.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import io.quarkus.test.junit.QuarkusTest;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class SiteMailTest {
    // one server per class on GreenMail's fixed test port; test classes run one after another, so they never share it
    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);
    @Inject
    TestStore store;
    @Inject
    SettingRepository settings;
    @Inject
    SiteMail mail;
    @Inject
    SiteReset siteReset;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("smtp_host", "localhost");
        settings.put("smtp_port", String.valueOf(ServerSetupTest.SMTP.getPort()));
        settings.put("smtp_security", "none");
        settings.put("smtp_from", "blog@example.org");
        mail.reload();
    }

    @Test
    void sendsPlainTextUtf8() throws Exception {
        mail.send(new Mail("reader@example.org", "Привет", "Тело письма — ünïcode"));
        var received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Привет");
        assertThat(received[0].getContentType()).startsWith("text/plain");
        assertThat(received[0].getContent()).asString().isEqualTo("Тело письма — ünïcode");
        assertThat(GreenMailUtil.getAddressList(received[0].getFrom())).isEqualTo("blog@example.org");
    }

    @Test
    void afterCommitSendsOnlyOnCommit() {
        assertThatCode(() -> QuarkusTransaction.requiringNew().run(() -> {
            mail.sendAfterCommit(new Mail("reader@example.org", "rolled back", "never sent"));
            throw new IllegalStateException("roll back");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(SMTP.getReceivedMessages()).isEmpty();

        QuarkusTransaction
            .requiringNew()
            .run(() -> mail.sendAfterCommit(new Mail("reader@example.org", "committed", "sent")));
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> m.getSubject())
            .containsExactly("committed");
    }

    @Test
    void outsideATransactionSendsAtOnce() {
        mail.sendAfterCommit(new Mail("reader@example.org", "now", "sent"));
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> m.getSubject())
            .containsExactly("now");
    }

    @Test
    void failureIsRecordedNotThrown() {
        closedPort();
        assertThatCode(() -> QuarkusTransaction
            .requiringNew()
            .run(() -> mail.sendAfterCommit(new Mail("reader@example.org", "lost", "lost"))))
            .doesNotThrowAnyException();
        var error = settings.get("mail_last_error").orElseThrow();
        assertThat(Instant.parse(error.substring(0, error.indexOf(' ')))).isBefore(Instant.now().plusSeconds(1));
        assertThat(error.substring(error.indexOf(' ') + 1)).isNotBlank();
    }

    @Test
    void failureOutsideATransactionIsRecordedNotThrown() {
        closedPort();
        assertThatCode(() -> mail.sendAfterCommit(new Mail("reader@example.org", "lost", "lost"))).doesNotThrowAnyException();
        assertThat(settings.get("mail_last_error")).isNotEmpty();
    }

    @Test
    void sendThrowsOnFailure() {
        closedPort();
        assertThatThrownBy(() -> mail.send(new Mail("reader@example.org", "lost", "lost")))
            .isInstanceOf(MailFailedException.class)
            .message()
            .isNotBlank();
    }

    @Test
    void reloadPicksUpNewSettings() throws Exception {
        mail.send(new Mail("reader@example.org", "first", "first"));
        settings.put("smtp_from", "other@example.org");
        mail.reload();
        mail.send(new Mail("reader@example.org", "second", "second"));
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> GreenMailUtil.getAddressList(m.getFrom()))
            .containsExactly("blog@example.org", "other@example.org");
    }

    @Test
    void siteWipeDropsTheClient() {
        mail.send(new Mail("reader@example.org", "before", "before"));
        siteReset.wipe();
        assertThatThrownBy(() -> mail.send(new Mail("reader@example.org", "after", "after")))
            .isInstanceOf(MailFailedException.class);
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    void configuredFollowsTheHost() {
        assertThat(mail.configured()).isTrue();
        settings.delete("smtp_host");
        assertThat(mail.configured()).isFalse();
    }

    @Test
    void textsComeInTheRecipientsLanguage() {
        assertThat(SiteMail.texts("en").testSubject("Blog")).isEqualTo("Test email from Blog");
        assertThat(SiteMail.texts("ru").testSubject("Blog")).isEqualTo("Тестовое письмо от Blog");
        assertThat(SiteMail.texts("ru").inviteBody("Ада", "Blog", "https://x/set?t=1", 7))
            .isEqualTo(
                    """
                    Здравствуйте, Ада!

                    У вас есть учётная запись на Blog. Задайте пароль по ссылке:

                    https://x/set?t=1

                    Ссылка действует 7 дн.
                    """
            );
    }

    private void closedPort() {
        try (var socket = new ServerSocket(0)) {
            settings.put("smtp_port", String.valueOf(socket.getLocalPort()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mail.reload();
    }
}
