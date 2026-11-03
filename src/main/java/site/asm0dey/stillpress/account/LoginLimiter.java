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

    @Inject
    public LoginLimiter(Clock clock) {
        this.clock = clock;
    }

    @Produces
    @Singleton
    static Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Time until the next attempt is allowed, or empty when allowed now.
     */
    public synchronized Optional<Duration> blocked(String login, String ip) {
        prune();
        var now = clock.instant();
        return Stream
            .of(wait(byLogin.get(key(login)), PER_ACCOUNT, now), wait(byIp.get(ip), PER_IP, now))
            .flatMap(Optional::stream)
            .max(Comparator.naturalOrder());
    }

    public synchronized void failure(String login, String ip) {
        prune();
        var now = clock.instant();
        byLogin
            .computeIfAbsent(key(login), _ -> new ArrayDeque<>())
            .addLast(now);
        byIp
            .computeIfAbsent(ip, _ -> new ArrayDeque<>())
            .addLast(now);
    }

    public synchronized void success(String login) {
        byLogin.remove(key(login));
    }

    synchronized void reset() {
        byLogin.clear();
        byIp.clear();
    }

    /**
     * Once {@code limit} failures are in the window, the wait ends when enough of the oldest leave it.
     */
    private static Optional<Duration> wait(ArrayDeque<Instant> failures, int limit, Instant now) {
        if (failures == null || failures.size() < limit) {
            return Optional.empty();
        }
        var oldestToExpire = failures.stream().skip(failures.size() - limit).findFirst().orElseThrow();
        return Optional.of(Duration.between(now, oldestToExpire.plus(WINDOW)));
    }

    /**
     * Drops failures older than the window, and keys left empty, so memory stays bounded by recent failures.
     */
    private void prune() {
        var cutoff = clock.instant().minus(WINDOW);
        for (var map : List.of(byLogin, byIp)) {
            map.values().removeIf(d -> {
                while (!d.isEmpty() && !d.peekFirst().isAfter(cutoff)) {
                    d.removeFirst();
                }
                return d.isEmpty();
            });
        }
    }

    private static String key(String login) {
        return login.toLowerCase(Locale.ROOT);
    }
}
