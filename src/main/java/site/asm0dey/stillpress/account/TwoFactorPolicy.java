package site.asm0dey.stillpress.account;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
    static final String KEY = "require_2fa";
    @Inject
    SettingRepository settings;
    @Inject
    TwoFactorRepository factors;
    @Inject
    SessionRepository sessions;

    public boolean required() {
        return settings.get(KEY).filter("true"::equals).isPresent();
    }

    /**
     * Turning it on needs an administrator who already has a factor, and signs out every session without one.
     */
    @Transactional
    public void set(boolean on, long adminId) {
        if (on && !factors.hasFactor(adminId)) {
            throw new ItemService.Refused(409, "admin_needs_2fa", null);
        }
        settings.put(KEY, String.valueOf(on));
        if (on) {
            sessions.deleteAllWithoutSecondFactor();
        }
    }
}
