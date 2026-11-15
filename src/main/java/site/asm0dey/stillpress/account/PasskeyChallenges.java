package site.asm0dey.stillpress.account;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Login challenges already spent. The WebAuthn extension keeps its challenge only in the client's cookie, so without
 * this a captured assertion and its cookie sign in again until the cookie expires. In memory: one install is one
 * process.
 */
@ApplicationScoped
public class PasskeyChallenges {
    private final Clock clock;
    // the challenge cookie is checked against session-timeout, not quarkus.webauthn.timeout (that only goes to the client)
    private final Duration ttl;
    private final Map<String, Instant> spent = new HashMap<>();
    private Instant lastSweep;

    @Inject
    public PasskeyChallenges(
            Clock clock,
            @ConfigProperty(name = "quarkus.webauthn.session-timeout", defaultValue = "30M") Duration ttl
    ) {
        this.clock = clock;
        this.ttl = ttl;
        this.lastSweep = clock.instant();
    }

    /**
     * True the first time this challenge is seen within its lifetime.
     */
    public synchronized boolean claim(String challenge) {
        var now = clock.instant();
        // whole-map prune at most once per lifetime, as LoginLimiter does
        if (!now.isBefore(lastSweep.plus(ttl))) {
            lastSweep = now;
            spent.values().removeIf(expires -> !expires.isAfter(now));
        }
        var expires = spent.get(challenge);
        if (expires != null && expires.isAfter(now)) {
            return false;
        }
        spent.put(challenge, now.plus(ttl));
        return true;
    }
}
