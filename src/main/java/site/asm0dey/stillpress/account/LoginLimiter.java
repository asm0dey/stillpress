package site.asm0dey.stillpress.account;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import module java.base;

/**
 * Login throttling: sliding 15-minute windows of failures, per account and per client IP. In memory: one install is
 * one process.
 */
@ApplicationScoped
public class LoginLimiter {
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int PER_ACCOUNT = 5;
    static final int PER_IP = 20;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> byLogin = new HashMap<>();
    private final Map<String, ArrayDeque<Instant>> byIp = new HashMap<>();
    private Instant lastSweep;

    @Inject
    public LoginLimiter(Clock clock) {
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    @Produces
    @Singleton
    static Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Time until this client IP may try again, or empty when allowed now.
     */
    public synchronized Optional<Duration> blockedIp(String ip) {
        var now = clock.instant();
        sweep(now);
        return wait(touch(byIp, ip, now), PER_IP, now);
    }

    /**
     * Time until this account may try again, or empty when allowed now. The key is case-insensitive.
     */
    public synchronized Optional<Duration> blockedAccount(String account) {
        var now = clock.instant();
        sweep(now);
        return wait(touch(byLogin, key(account), now), PER_ACCOUNT, now);
    }

    public synchronized void failure(String account, String ip) {
        var now = clock.instant();
        sweep(now);
        record(byLogin, key(account), now);
        record(byIp, ip, now);
    }

    /**
     * A failure that names no real account (an oversized login); only the IP bucket counts it.
     */
    public synchronized void ipFailure(String ip) {
        var now = clock.instant();
        sweep(now);
        record(byIp, ip, now);
    }

    public synchronized void success(String account) {
        byLogin.remove(key(account));
    }

    /**
     * Once {@code limit} failures are in the window, the wait ends when enough of the oldest leave it.
     */
    // a list size minus a small limit cannot overflow
    @SuppressWarnings("java:S2184")
    private static Optional<Duration> wait(ArrayDeque<Instant> failures, int limit, Instant now) {
        if (failures == null || failures.size() < limit) {
            return Optional.empty();
        }
        var oldestToExpire = failures.stream().skip(failures.size() - limit).findFirst().orElseThrow();
        return Optional.of(Duration.between(now, oldestToExpire.plus(WINDOW)));
    }

    /**
     * Total keys in both maps, for the sweep test.
     */
    synchronized int size() {
        return byLogin.size() + byIp.size();
    }

    /**
     * The key's failures inside the window; drops the older ones, and the key when none are left.
     */
    private static ArrayDeque<Instant> touch(Map<String, ArrayDeque<Instant>> map, String k, Instant now) {
        var d = map.get(k);
        if (d != null && trim(d, now)) {
            map.remove(k);
            return null;
        }
        return d;
    }

    /**
     * Drops failures that left the window; true when none are left.
     */
    private static boolean trim(ArrayDeque<Instant> d, Instant now) {
        var cutoff = now.minus(WINDOW);
        while (!d.isEmpty() && !d.peekFirst().isAfter(cutoff)) {
            d.removeFirst();
        }
        return d.isEmpty();
    }

    private static void record(Map<String, ArrayDeque<Instant>> map, String k, Instant now) {
        touch(map, k, now);
        map
            .computeIfAbsent(k, _ -> new ArrayDeque<>())
            .addLast(now);
    }

    /**
     * Whole-map prune, at most once per window, so keys nobody touches again do not accumulate.
     */
    private void sweep(Instant now) {
        if (now.isBefore(lastSweep.plus(WINDOW))) {
            return;
        }
        lastSweep = now;
        for (var map : List.of(byLogin, byIp)) {
            map.values().removeIf(d -> trim(d, now));
        }
    }

    private static String key(String account) {
        return account.toLowerCase(Locale.ROOT);
    }
}
