package site.asm0dey.stillpress.account;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;

/**
 * Issues a single-use set-password link: {@code <admin_url>/admin/set-password?token=...}, path only while admin_url is unset.
 */
@ApplicationScoped
public class SetPasswordLink {
    public static final Duration INVITE_TTL = Duration.ofDays(7);
    public static final Duration RESET_TTL = Duration.ofHours(1);
    public static final Duration MANUAL_TTL = Duration.ofHours(24);
    @Inject
    TokenRepository tokens;
    @Inject
    SettingRepository settings;

    public String issue(long userId, String purpose, Duration ttl) {
        return link(tokens.create(userId, purpose, ttl));
    }

    /**
     * Like {@link #issue}, but empty when the user already got a link of this purpose at or after {@code since}.
     */
    public Optional<String> issueUnlessIssuedSince(long userId, String purpose, Duration ttl, Instant since) {
        return tokens.createUnlessIssuedSince(userId, purpose, ttl, since).map(this::link);
    }

    // the lookbehind keeps the regex linear
    @SuppressWarnings("java:S8786")
    private String link(String token) {
        var base = settings.get("admin_url").orElse("").replaceAll("(?<!/)/+$", "");
        return base + "/admin/set-password?token=" + token;
    }
}
