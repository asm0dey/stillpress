package site.asm0dey.stillpress.account;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import module java.base;

/**
 * Quarkus tests run the shared in-memory {@link LoginLimiter} on a clock that only moves when told to; moving it past
 * the window forgets every failure.
 */
public final class Limiters {
    @Produces
    @Singleton
    @Alternative
    @Priority(1)
    static final MutableClock CLOCK = new MutableClock(Instant.now());

    public static void reset() {
        CLOCK.advance(LoginLimiter.WINDOW);
    }
}
