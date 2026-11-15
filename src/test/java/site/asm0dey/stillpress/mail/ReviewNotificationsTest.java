package site.asm0dey.stillpress.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ReviewNotificationsTest {
    // one server per class on GreenMail's fixed test port; test classes run one after another, so they never share it
    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    SiteMail mail;
    @Inject
    ItemService items;
    @Inject
    ItemRepository repo;
    long contributorId;
    long editorId;

    @BeforeEach
    void reset() {
        store.reset();
        settings.put("title", "Blog");
        settings.put("admin_url", "https://blog.example.org");
        settings.put("smtp_host", "localhost");
        settings.put("smtp_port", String.valueOf(ServerSetupTest.SMTP.getPort()));
        settings.put("smtp_security", "none");
        settings.put("smtp_from", "blog@example.org");
        mail.reload();
        users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR);
        editorId = users.create("ed", "ed@example.org", "Ed", Role.EDITOR).id();
        users.create("writer", "writer@example.org", "Writer", Role.AUTHOR);
        users.create("noemail", null, "No Email", Role.EDITOR);
        contributorId = users.create("newbie", "newbie@example.org", "Newbie", Role.CONTRIBUTOR).id();
    }

    @Test
    void contributorSubmitMailsAdminsAndEditors() throws Exception {
        var item = items.create("post", contributorId);
        submit(item.getId(), "Hello", contributorId, Role.CONTRIBUTOR);
        assertThat(SMTP.waitForIncomingEmail(5000, 2)).isTrue();
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> m.getAllRecipients()[0].toString())
            .containsExactlyInAnyOrder("admin@example.org", "ed@example.org");
        var first = SMTP.getReceivedMessages()[0];
        assertThat(first.getSubject()).isEqualTo("Pending review on Blog: Hello");
        assertThat(first.getContent().toString())
            .contains("Newbie submitted \"Hello\"")
            .contains("https://blog.example.org/admin/edit/" + item.getId());
    }

    @Test
    void resavingPendingSendsNothing() throws Exception {
        var item = items.create("post", contributorId);
        submit(item.getId(), "Hello", contributorId, Role.CONTRIBUTOR);
        SMTP.waitForIncomingEmail(5000, 2);
        SMTP.purgeEmailFromAllMailboxes();
        var stored = items.update(
                item.getId(),
                update("Hello again", "pending", modifiedAt(item.getId())),
                new ItemService.Actor(contributorId, Role.CONTRIBUTOR)
        );
        assertThat(stored.getTitle()).isEqualTo("Hello again");
        assertThat(SMTP.waitForIncomingEmail(1000, 1)).isFalse();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void editorSubmittingOwnPostSkipsThemselves() {
        var item = items.create("post", editorId);
        submit(item.getId(), "Mine", editorId, Role.EDITOR);
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> m.getAllRecipients()[0].toString())
            .containsExactly("admin@example.org");
    }

    @Test
    void rollbackSendsNothing() {
        var item = items.create("post", contributorId);
        var stale = modifiedAt(item.getId()).minusDays(1);
        assertThatThrownBy(() -> items.update(
                item.getId(),
                update("Hello", "pending", stale),
                new ItemService.Actor(contributorId, Role.CONTRIBUTOR)
        ))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(409));
        assertThat(SMTP.waitForIncomingEmail(1000, 1)).isFalse();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void crlfInTitleCannotInjectHeaders() throws Exception {
        var item = items.create("post", contributorId);
        submit(item.getId(), "Hi\r\nBcc: evil@example.org", contributorId, Role.CONTRIBUTOR);
        assertThat(SMTP.waitForIncomingEmail(5000, 2)).isTrue();
        assertThat(SMTP.getReceivedMessages())
            .extracting(m -> m.getAllRecipients().length + ":" + m.getAllRecipients()[0])
            .containsExactlyInAnyOrder("1:admin@example.org", "1:ed@example.org");
        for (var m : SMTP.getReceivedMessages()) {
            assertThat(m.getHeader("Bcc")).isNull();
            assertThat(m.getSubject()).doesNotContain("\r", "\n");
        }
    }

    private LocalDateTime modifiedAt(long id) {
        return repo.find(id).orElseThrow().getModifiedAt();
    }

    private void submit(long id, String title, long userId, Role role) {
        items.update(id, update(title, "pending", modifiedAt(id)), new ItemService.Actor(userId, role));
    }

    private static ItemService.Update update(String title, String status, LocalDateTime expected) {
        return new ItemService.Update(
                title,
                null,
                null,
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                expected
        );
    }
}
