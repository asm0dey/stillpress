package site.asm0dey.stillpress.account;

import module java.base;

/**
 * User roles; {@link #id()} is the string stored in {@code author.role} and used as the security role.
 */
public enum Role {
    ADMINISTRATOR,
    EDITOR,
    AUTHOR,
    CONTRIBUTOR;
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role of(String id) {
        for (var r : values()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown role: " + id);
    }
}
