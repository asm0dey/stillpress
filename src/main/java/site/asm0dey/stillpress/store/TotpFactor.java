package site.asm0dey.stillpress.store;

/**
 * A user's authenticator-app seed, encrypted by {@code Secrets}; usable once {@code confirmed}.
 */
public record TotpFactor(long userId, String ciphertext, boolean confirmed, long lastStep) {}
