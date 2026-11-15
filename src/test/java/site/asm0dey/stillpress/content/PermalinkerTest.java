package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ExtendWith(MockitoExtension.class)
class PermalinkerTest {
    @Mock
    ItemRepository items;
    @Mock
    AuthorRepository authors;
    @Mock
    SettingRepository settings;
    @Mock
    RedirectRepository redirects;
    @Mock
    TermRepository terms;
    @Mock
    TermService termService;
    Permalinker permalinker;

    @BeforeEach
    void setUp() {
        permalinker = new Permalinker(items, authors, settings, redirects, terms, termService);
    }

    @Test
    void zoneFollowsTheTimezoneSetting() {
        when(settings.get("timezone")).thenReturn(Optional.of("Pacific/Kiritimati"));
        assertThat(permalinker.zone()).isEqualTo(ZoneId.of("Pacific/Kiritimati"));
    }

    @Test
    void zoneDefaultsToUtc() {
        when(settings.get("timezone")).thenReturn(Optional.empty());
        assertThat(permalinker.zone()).isEqualTo(ZoneId.of("UTC"));
    }
}
