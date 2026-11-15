package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import module java.base;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.db.tables.pojos.TotpFactor;
import site.asm0dey.stillpress.store.SecretRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * One-time use of a step is the repository's compare-and-set; see {@code TwoFactorRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class TotpTest {
    static final byte[] SEED = "12345678901234567890".getBytes(UTF_8);
    static final long USER = 5;
    @TempDir
    Path root;
    @Mock
    TwoFactorRepository factors;
    @Mock
    SettingRepository settings;
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(59));
    Totp totp;
    String ciphertext;

    @BeforeEach
    void setUp() throws GeneralSecurityException {
        // real crypto: the seed is stored under the AEAD keyset Keys generates in the temporary data dir
        var secrets = new Secrets(
                org.mockito.Mockito.mock(SecretRepository.class),
                factors,
                new DataDir(root.toString()),
                Optional.empty()
        );
        totp = new Totp(clock, secrets, factors, settings);
        ciphertext = secrets.encrypt("totp:" + USER, Base64.getEncoder().encodeToString(SEED));
    }

    private Optional<TotpFactor> row(boolean confirmed) {
        return Optional.of(new TotpFactor(USER, ciphertext, confirmed, 0L, LocalDateTime.now()));
    }

    static String code(long epochSecond) throws Exception {
        return new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1")
            .generateOneTimePasswordString(new SecretKeySpec(SEED, "HmacSHA1"), Instant.ofEpochSecond(epochSecond));
    }

    @Test
    void rfc6238Vector() {
        when(factors.totp(USER)).thenReturn(row(true));
        when(factors.advanceTotp(USER, 1)).thenReturn(true);
        assertThat(totp.verify(USER, "287082")).isTrue();
    }

    @Test
    void totpCodeCannotBeReused() {
        when(factors.totp(USER)).thenReturn(row(true));
        // the repository accepts a step once
        when(factors.advanceTotp(USER, 1)).thenReturn(true, false);
        assertThat(totp.verify(USER, "287082")).isTrue();
        assertThat(totp.verify(USER, "287082")).isFalse();
    }

    @Test
    void acceptsOneStepOfDrift() throws Exception {
        when(factors.totp(USER)).thenReturn(row(true));
        when(factors.advanceTotp(eq(USER), anyLong())).thenReturn(true);
        clock.advance(Duration.ofSeconds(3000));
        var now = clock.instant().getEpochSecond();
        assertThat(totp.verify(USER, code(now - 60))).isFalse();
        assertThat(totp.verify(USER, code(now + 60))).isFalse();
        assertThat(totp.verify(USER, code(now - 30))).isTrue();
        assertThat(totp.verify(USER, code(now + 30))).isTrue();
    }

    @Test
    void rejectsNonDigits() {
        assertThat(totp.verify(USER, "12345a")).isFalse();
        assertThat(totp.verify(USER, " 123456")).isFalse();
        assertThat(totp.verify(USER, "1234567")).isFalse();
        assertThat(totp.verify(USER, null)).isFalse();
    }

    @Test
    void confirmingCodeIsSpentForVerify() {
        when(factors.totp(USER)).thenReturn(row(false), row(true));
        when(factors.confirmTotp(USER, ciphertext, 1)).thenReturn(true);
        // confirming records its step, so the repository refuses it to verify
        when(factors.advanceTotp(USER, 1)).thenReturn(false);
        assertThat(totp.confirm(USER, "287082")).isTrue();
        assertThat(totp.verify(USER, "287082")).isFalse();
    }

    @Test
    void unconfirmedSeedDoesNotVerifyButConfirms() {
        when(factors.totp(USER)).thenReturn(row(false), row(false), row(true));
        when(factors.confirmTotp(USER, ciphertext, 1)).thenReturn(true);
        when(factors.advanceTotp(USER, 1)).thenReturn(false);
        assertThat(totp.verify(USER, "287082")).isFalse();
        verify(factors, never()).advanceTotp(eq(USER), anyLong());
        assertThat(totp.confirm(USER, "287082")).isTrue();
        assertThat(totp.verify(USER, "287082")).isFalse();
    }
}
