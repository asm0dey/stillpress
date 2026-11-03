package site.asm0dey.stillpress.account;

/**
 * Lets tests in other packages clear the shared in-memory {@link LoginLimiter}.
 */
public final class Limiters {
    private Limiters() {
    }

    public static void reset(LoginLimiter limiter) {
        limiter.reset();
    }
}
