package site.asm0dey.stillpress.account;

import module java.base;

/**
 * Validation of a user's login, email and display name, shared by user creation and profile edits. Callers pass the
 * trimmed values and store them trimmed; limits are the {@code author} columns'.
 */
public final class UserFields {
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9 _.\\-@]+");

    private UserFields() {
    }

    public static String trim(String v) {
        return v == null ? null : v.strip();
    }

    public static boolean validLogin(String v) {
        return v != null && !v.isEmpty() && v.length() <= 60 && LOGIN.matcher(v).matches();
    }

    public static boolean validEmail(String v) {
        return v != null && v.length() <= 100 && v.indexOf('@') >= 1;
    }

    public static boolean validDisplayName(String v) {
        return v != null && !v.isEmpty() && v.length() <= 250;
    }
}
