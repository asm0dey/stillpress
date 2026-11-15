package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The privacy notice comes from a Qute message bundle, which exists only in the running application.
 */
@QuarkusTest
class CaptchaQuarkusTest {
    @Inject
    Captcha captcha;
    @Inject
    TestStore store;
    @Inject
    SettingRepository settings;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void privacyNoticeNamesTheProvider() {
        assertThat(captcha.privacyNotice("en")).isEmpty();
        settings.put("captcha_provider", "turnstile");
        assertThat(captcha.privacyNotice("en")).contains("Cloudflare Turnstile");
        settings.put("captcha_provider", "altcha");
        assertThat(captcha.privacyNotice("ru")).startsWith("Эта форма защищена ALTCHA");
    }
}
