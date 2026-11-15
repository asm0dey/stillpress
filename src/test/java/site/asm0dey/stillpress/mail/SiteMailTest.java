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
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("with host", mail.configured());
        when(settings.get("smtp_host")).thenReturn(Optional.empty());
        answers.put("without host", mail.configured());
        assertThat(answers).containsExactly(Map.entry("with host", true), Map.entry("without host", false));
    }
}
