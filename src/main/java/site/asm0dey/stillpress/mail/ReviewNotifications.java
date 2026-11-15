package site.asm0dey.stillpress.mail;

import jakarta.enterprise.context.ApplicationScoped;
import site.asm0dey.stillpress.admin.AuthResource;
import site.asm0dey.stillpress.admin.UsersResource;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Tells the administrators and editors that an item waits for review.
 */
@ApplicationScoped
public class ReviewNotifications {
    private final SiteMail mail;
    private final AuthorRepository authors;
    private final SettingRepository settings;

    ReviewNotifications(SiteMail mail, AuthorRepository authors, SettingRepository settings) {
        this.mail = mail;
        this.authors = authors;
        this.settings = settings;
    }

    /**
     * Mails every reviewer except {@code actorId} once the current transaction commits; nothing without SMTP.
     */
    public void pendingReview(long itemId, String title, long actorId) {
        if (!mail.configured()) {
            return;
        }
        var site = settings.get("title").orElse("Stillpress");
        var link = settings.get("admin_url").orElse("") + "/admin/edit/" + itemId;
        var author = authors.findById(actorId).map(UsersResource::displayName).orElse("");
        for (var r : authors.reviewers()) {
            if (r.id() == actorId) {
                continue;
            }
            var texts = SiteMail.texts(AuthResource.adminLocale(r.locale(), settings.get("language")));
            mail.sendAfterCommit(
                    new Mail(r.email(), texts.reviewSubject(site, title), texts.reviewBody(author, title, link))
            );
        }
    }
}
