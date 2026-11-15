package site.asm0dey.stillpress.account;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class PasskeyChallengesTest {
    @Test
    void aChallengeIsSpentOnceWithinItsLifetime() {
        var clock = new MutableClock(Instant.ofEpochSecond(1000));
        var challenges = new PasskeyChallenges(clock, Duration.ofMinutes(30));
        assertThat(challenges.claim("a")).isTrue();
        assertThat(challenges.claim("a")).isFalse();
        assertThat(challenges.claim("b")).isTrue();
        clock.advance(Duration.ofMinutes(29));
        assertThat(challenges.claim("a")).isFalse();
        // past its lifetime the cookie is refused anyway, so the entry may go
        clock.advance(Duration.ofMinutes(1));
        assertThat(challenges.claim("a")).isTrue();
    }
}
