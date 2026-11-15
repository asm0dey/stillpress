package site.asm0dey.stillpress.account;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * The site-wide {@code require_2fa} setting: when on, a session without a second factor may only enroll one.
 */
@ApplicationScoped
public class TwoFactorPolicy {
    public static final String KEY = "require_2fa";
    private final SettingRepository settings;
    private final TwoFactorRepository factors;
    private final SessionRepository sessions;

    TwoFactorPolicy(SettingRepository settings, TwoFactorRepository factors, SessionRepository sessions) {
        this.settings = settings;
        this.factors = factors;
        this.sessions = sessions;
    }

    public boolean required() {
        return settings.get(KEY).filter("true"::equals).isPresent();
    }

    /**
     * Turning it on needs an administrator who already has a factor, and signs out every session without one. Saying
     * "on" again leaves the sessions alone: those without a factor are already fenced to enrolling one.
     */
    @Transactional
    public void set(boolean on, long adminId) {
        if (on && !factors.hasFactor(adminId)) {
            throw new ItemService.Refused(409, "admin_needs_2fa", null);
        }
        var was = required();
        settings.put(KEY, String.valueOf(on));
        if (on && !was) {
            sessions.deleteAllWithoutSecondFactor();
        }
    }
}
