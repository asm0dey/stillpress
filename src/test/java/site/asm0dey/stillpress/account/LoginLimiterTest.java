package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class LoginLimiterTest {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
    LoginLimiter l = new LoginLimiter(clock);

    @Test
    void fiveFailuresBlockTheAccountForTheRestOfTheWindow() {
        for (int i = 0; i < 5; i++) {
            l.failure("Admin", "10.0.0." + i);
        }
        assertThat(l.blockedAccount("admin")).contains(Duration.ofMinutes(15));
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(l.blockedAccount("admin")).isEmpty();
    }

    @Test
    void fourFailuresDoNotBlock() {
        for (int i = 0; i < 4; i++) {
            l.failure("admin", "10.0.0.1");
        }
        assertThat(l.blockedAccount("admin")).isEmpty();
    }

    @Test
    void windowSlides() {
        l.failure("admin", "10.0.0.1");
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 4; i++) {
            l.failure("admin", "10.0.0.1");
        }
        // the oldest failure leaves the window 5 minutes from now
        assertThat(l.blockedAccount("admin")).contains(Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        assertThat(l.blockedAccount("admin")).isEmpty();
    }

    @Test
    void twentyFailuresAcrossAccountsBlockTheIp() {
        for (int i = 0; i < 20; i++) {
            l.failure("user" + i, "10.0.0.1");
        }
        assertThat(l.blockedIp("10.0.0.1")).isPresent();
        assertThat(l.blockedIp("10.0.0.2")).isEmpty();
    }

    @Test
    void successClearsTheAccountButNotTheIp() {
        for (int i = 0; i < 4; i++) {
            l.failure("admin", "10.0.0.1");
        }
        l.success("ADMIN");
        l.failure("admin", "10.0.0.1");
        assertThat(l.blockedAccount("admin")).isEmpty();
        for (int i = 0; i < 15; i++) {
            l.failure("other" + i, "10.0.0.1");
        }
        assertThat(l.blockedIp("10.0.0.1")).isPresent();
    }
}
