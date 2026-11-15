package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Captcha;
import site.asm0dey.stillpress.content.ItemService;

@ExtendWith(MockitoExtension.class)
class CaptchaResourceTest {
    @Mock
    Captcha captcha;
    CaptchaResource resource;

    @BeforeEach
    void setUp() {
        resource = new CaptchaResource(captcha);
    }

    private void turnstileConfigured() {
        when(captcha.provider()).thenReturn("turnstile");
        when(captcha.turnstileSiteKey()).thenReturn(Optional.of("site-key"));
        when(captcha.turnstileSecretSet()).thenReturn(true);
        when(captcha.locked()).thenReturn(false);
    }

    @Test
    void getShowsTheProviderSiteKeyAndWhetherASecretIsSetButNeverTheSecret() {
        turnstileConfigured();
        assertThat(resource.get()).isEqualTo(new CaptchaResource.Settings("turnstile", "site-key", true, false));
    }

    @Test
    void getWithoutASiteKeyHasNull() {
        when(captcha.provider()).thenReturn("none");
        when(captcha.turnstileSiteKey()).thenReturn(Optional.empty());
        when(captcha.locked()).thenReturn(true);
        assertThat(resource.get()).isEqualTo(new CaptchaResource.Settings("none", null, false, true));
    }

    @Test
    void putSavesThenAnswersTheCurrentSettings() {
        turnstileConfigured();
        var saved = resource.put(new CaptchaResource.Update("turnstile", "site-key", "s3cret", "solved"));
        verify(captcha).save("turnstile", "site-key", "s3cret", "solved");
        assertThat(saved).isEqualTo(new CaptchaResource.Settings("turnstile", "site-key", true, false));
    }

    @Test
    void putWithoutABodyIs400OnProviderAndSavesNothing() {
        assertThatThrownBy(() -> resource.put(null)).isInstanceOfSatisfying(ItemService.Refused.class, e -> {
            assertThat(e.status).isEqualTo(400);
            assertThat(e.body()).isEqualTo(Map.of("error", "invalid", "field", "provider"));
        });
        verifyNoMoreInteractions(captcha);
    }

    @Test
    void aRefusalFromTheCaptchaIsItsStatusAndBody() {
        try (var r = resource.refused(new ItemService.Refused(502, "turnstile_unreachable", null))) {
            assertThat(r.getStatus()).isEqualTo(502);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "turnstile_unreachable"));
        }
        try (var r = resource.refused(new ItemService.Refused(400, "invalid", "turnstileSiteKey"))) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "turnstileSiteKey"));
        }
    }
}
