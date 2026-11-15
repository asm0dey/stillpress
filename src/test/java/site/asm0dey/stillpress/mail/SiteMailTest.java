package site.asm0dey.stillpress.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import io.vertx.core.Vertx;
import jakarta.enterprise.event.Event;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.store.SettingRepository;

@ExtendWith(MockitoExtension.class)
class SiteMailTest {
    @Mock
    Vertx vertx;
    @Mock
    SettingRepository settings;
    @Mock
    Secrets secrets;
    @Mock
    Event<Mail> afterCommit;
    SiteMail mail;

    @BeforeEach
    void setUp() {
        mail = new SiteMail(vertx, settings, secrets, afterCommit);
    }

    @Test
    void configuredFollowsTheHost() {
        when(settings.get("smtp_host")).thenReturn(Optional.of("localhost"));
        assertThat(mail.configured()).isTrue();
        when(settings.get("smtp_host")).thenReturn(Optional.empty());
        assertThat(mail.configured()).isFalse();
    }
}
