package site.asm0dey.stillpress.account;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;

/**
 * Issues a single-use set-password link: {@code <home_url>/admin/set-password?token=...}, path only while home_url is unset.
 */
@ApplicationScoped
public class SetPasswordLink {
    @Inject
    TokenRepository tokens;
    @Inject
    SettingRepository settings;

    public String issue(long userId) {
        var token = tokens.create(userId, TokenRepository.SET_PASSWORD, TokenRepository.SET_PASSWORD_TTL);
        var home = settings.get("home_url").orElse("").replaceAll("(?<!/)/+$", "");
        return home + "/admin/set-password?token=" + token;
    }
}
