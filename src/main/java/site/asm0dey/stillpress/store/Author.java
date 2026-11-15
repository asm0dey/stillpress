package site.asm0dey.stillpress.store;

/**
 * A user: an {@code author} row. {@code passwordHash} is null for an author who cannot sign in; {@code sourceId} is the imported WordPress user id.
 */
public record Author(
        long id,
        String login,
        String displayName,
        String email,
        String slug,
        String role,
        String passwordHash,
        String locale,
        Long sourceId
) {}
