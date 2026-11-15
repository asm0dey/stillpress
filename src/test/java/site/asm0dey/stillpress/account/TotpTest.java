package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
class TotpTest {
    static final byte[] SEED = "12345678901234567890".getBytes(UTF_8);
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    Secrets secrets;
    @Inject
    TwoFactorRepository factors;
    @Inject
    SettingRepository settings;
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(59));
    Totp totp;
    long user;

    @BeforeEach
    void enroll() {
        store.reset();
        totp = new Totp(clock, secrets, factors, settings);
        user = users.create("admin", "admin@example.org", "Admin", Role.ADMINISTRATOR).id();
        factors.putTotp(user, secrets.encrypt("totp:" + user, Base64.getEncoder().encodeToString(SEED)));
        confirm();
    }

    void confirm() {
        var c = factors.totp(user).orElseThrow().ciphertext();
        assertThat(factors.confirmTotp(user, c, 0)).isTrue();
    }

    static String code(long epochSecond) throws Exception {
        return new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1")
            .generateOneTimePasswordString(new SecretKeySpec(SEED, "HmacSHA1"), Instant.ofEpochSecond(epochSecond));
    }

    @Test
    void rfc6238Vector() {
        assertThat(totp.verify(user, "287082")).isTrue();
    }

    @Test
    void totpCodeCannotBeReused() {
        assertThat(totp.verify(user, "287082")).isTrue();
        assertThat(totp.verify(user, "287082")).isFalse();
    }

    @Test
    void concurrentUseOfOneCodeSucceedsOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return totp.verify(user, "287082");
                }));
            }
            start.countDown();
            var wins = 0;
            for (var r : results) {
                wins += r.get() ? 1 : 0;
            }
            assertThat(wins).isEqualTo(1);
        }
    }

    @Test
    void acceptsOneStepOfDrift() throws Exception {
        clock.advance(Duration.ofSeconds(3000));
        var now = clock.instant().getEpochSecond();
        assertThat(totp.verify(user, code(now - 60))).isFalse();
        assertThat(totp.verify(user, code(now + 60))).isFalse();
        assertThat(totp.verify(user, code(now - 30))).isTrue();
        assertThat(totp.verify(user, code(now + 30))).isTrue();
    }

    @Test
    void rejectsNonDigits() {
        assertThat(totp.verify(user, "12345a")).isFalse();
        assertThat(totp.verify(user, " 123456")).isFalse();
        assertThat(totp.verify(user, "1234567")).isFalse();
        assertThat(totp.verify(user, null)).isFalse();
    }

    @Test
    void confirmingCodeIsSpentForVerify() {
        factors.deleteTotp(user);
        factors.putTotp(user, secrets.encrypt("totp:" + user, Base64.getEncoder().encodeToString(SEED)));
        assertThat(totp.confirm(user, "287082")).isTrue();
        assertThat(totp.verify(user, "287082")).isFalse();
    }

    @Test
    void unconfirmedSeedDoesNotVerifyButConfirms() {
        factors.deleteTotp(user);
        factors.putTotp(user, secrets.encrypt("totp:" + user, Base64.getEncoder().encodeToString(SEED)));
        assertThat(totp.verify(user, "287082")).isFalse();
        assertThat(totp.confirm(user, "287082")).isTrue();
        assertThat(factors.totp(user)).hasValueSatisfying(t -> assertThat(t.confirmed()).isTrue());
        assertThat(totp.verify(user, "287082")).isFalse();
    }
}
