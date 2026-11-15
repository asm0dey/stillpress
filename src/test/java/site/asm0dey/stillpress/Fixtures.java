package site.asm0dey.stillpress;

import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.Item;
import site.asm0dey.stillpress.db.tables.pojos.Revision;

/**
 * Records for resource unit tests: what a repository returns, with only the fields a test cares about set.
 */
public final class Fixtures {
    public static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    private Fixtures() {
    }

    public static Item item(long id, String type, String status, Long authorId) {
        return item(id, type, status, authorId, T0, T0);
    }

    /**
     * An item with its dates; a draft has no {@code publishedAt}.
     */
    public static Item item(
            long id,
            String type,
            String status,
            Long authorId,
            LocalDateTime publishedAt,
            LocalDateTime modifiedAt
    ) {
        return new Item(
                id,
                id,
                type,
                status,
                false,
                "Title " + id,
                "slug-" + id,
                "/slug-" + id + "/",
                "body",
                "blocks",
                "excerpt",
                authorId,
                null,
                publishedAt,
                modifiedAt,
                "guid",
                "open",
                0,
                null,
                publishedAt
        );
    }

    public static Revision revision(long id, String type, long objectId, boolean autosave, LocalDateTime at) {
        return revision(id, type, objectId, autosave, at, 1L);
    }

    public static Revision revision(
            long id,
            String type,
            long objectId,
            boolean autosave,
            LocalDateTime at,
            long authorId
    ) {
        return new Revision(id, type, objectId, "rt", "rb", "blocks", "re", authorId, at, autosave);
    }

    /**
     * A user as the repository returns it: login-derived slug and email, no locale.
     */
    public static Author author(long id, String login, Role role, String passwordHash) {
        return new Author(
                id,
                login,
                login,
                login + "@example.org",
                login,
                null,
                role.id(),
                passwordHash,
                null,
                login + "@example.org"
        );
    }
}
