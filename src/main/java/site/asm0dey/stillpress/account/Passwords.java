package site.asm0dey.stillpress.account;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.BadParametersException;
import com.password4j.types.Argon2;

/**
 * Argon2id password hashes as PHC strings.
 */
public final class Passwords {
    // OWASP minimum for Argon2id: 19 MiB memory, 2 iterations, 1 lane
    private static final Argon2Function ARGON2 = Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID);
    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 1024;

    private Passwords() {
    }

    public static String hash(String password) {
        return Password.hash(password).addRandomSalt(16).with(ARGON2).getResult();
    }

    /**
     * {@code false} for a {@code null} hash (imported users cannot log in until they set a password), a {@code null}
     * password, or a malformed hash: a login attempt must fail, never error.
     */
    public static boolean verify(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }
        try {
            return Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash));
        } catch (BadParametersException e) {
            return false;
        }
    }

    public static void validate(String password) {
        var n = password == null ? 0 : password.codePointCount(0, password.length());
        if (n < MIN_LENGTH || n > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long"
            );
        }
    }
}
