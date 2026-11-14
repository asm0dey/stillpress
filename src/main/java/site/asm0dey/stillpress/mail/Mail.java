package site.asm0dey.stillpress.mail;

/**
 * One plain-text, UTF-8 mail to one recipient.
 */
public record Mail(String to, String subject, String body) {}
