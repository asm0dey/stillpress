package site.asm0dey.stillpress.mail;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import site.asm0dey.stillpress.admin.AuthResource;
import site.asm0dey.stillpress.admin.UsersResource;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * Tells the administrators and editors that an item waits for review.
 */
@ApplicationScoped
public class ReviewNotifications {
    @Inject
    SiteMail mail;
    @Inject
    AuthorRepository authors;
    @Inject
    SettingRepository settings;

    /**
     * Mails every reviewer except {@code actorId} once the current transaction commits; nothing without SMTP.
     */
    public void pendingReview(ItemRecord item, long actorId) {
        if (!mail.configured()) {
            return;
        }
        var site = settings.get("title").orElse("Stillpress");
        var link = settings.get("admin_url").orElse("") + "/admin/edit/" + item.getId();
        var author = authors.findById(actorId).map(UsersResource::displayName).orElse("");
        for (var r : authors.reviewers()) {
            if (r.getId() == actorId) {
                continue;
            }
            var texts = SiteMail.texts(AuthResource.adminLocale(r.getLocale(), settings.get("language")));
            mail.sendAfterCommit(
                    new Mail(
                            r.getEmail(),
                            texts.reviewSubject(site, item.getTitle()),
                            texts.reviewBody(author, item.getTitle(), link)
                    )
            );
        }
    }
}
