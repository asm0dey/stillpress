package site.asm0dey.stillpress.mail;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * Subjects and bodies of the site's mails, in English; Russian in {@code messages/mail_ru.properties}. Get one in a
 * recipient's language with {@link SiteMail#texts}.
 */
@MessageBundle(value = "mail", locale = "en")
public interface MailTexts {
    @Message("You are invited to {site}")
    String inviteSubject(String site);

    @Message(
            """
            Hello {name},

            You have an account on {site}. Choose a password here:

            {link}

            The link works for {days} days.
            """
    )
    String inviteBody(String name, String site, String link, int days);

    @Message("Reset your password on {site}")
    String resetSubject(String site);

    @Message(
            """
            Hello {name},

            Someone asked to reset your password on {site}. Choose a new one here:

            {link}

            The link works for {minutes} minutes. If you did not ask for it, ignore this mail; your password stays as it is.
            """
    )
    String resetBody(String name, String site, String link, int minutes);

    @Message("Pending review on {site}: {title}")
    String reviewSubject(String site, String title);

    @Message("""
            {author} submitted "{title}" for review:

            {link}
            """)
    String reviewBody(String author, String title, String link);

    @Message("Test email from {site}")
    String testSubject(String site);

    @Message("""
            This is a test email from {site}. Mail is set up correctly.
            """)
    String testBody(String site);
}
