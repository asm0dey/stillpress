package site.asm0dey.stillpress.mail;

/**
 * The SMTP server could not be reached or refused the mail; the message says why, for the admin to read.
 */
public class MailFailedException extends RuntimeException {
    public MailFailedException(String message) {
        super(message);
    }
}
