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
    @Mock
    SecretRepository secretRepository;
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(59));
    Totp totp;
    String ciphertext;

    @BeforeEach
    void setUp() throws GeneralSecurityException {
        // real crypto: the seed is stored under the AEAD keyset Keys generates in the temporary data dir
        var secrets = new Secrets(secretRepository, factors, new DataDir(root.toString()), Optional.empty());
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
        assertThat(Map.of("accepted", totp.verify(USER, "287082"))).containsExactly(Map.entry("accepted", true));
    }

    @Test
    void totpCodeCannotBeReused() {
        when(factors.totp(USER)).thenReturn(row(true));
        // the repository accepts a step once
        when(factors.advanceTotp(USER, 1)).thenReturn(true, false);
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("first use", totp.verify(USER, "287082"));
        answers.put("replay", totp.verify(USER, "287082"));
        assertThat(answers).containsExactly(Map.entry("first use", true), Map.entry("replay", false));
    }

    @Test
    void acceptsOneStepOfDrift() throws Exception {
        when(factors.totp(USER)).thenReturn(row(true));
        when(factors.advanceTotp(eq(USER), anyLong())).thenReturn(true);
        clock.advance(Duration.ofSeconds(3000));
        var now = clock.instant().getEpochSecond();
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("two steps back", totp.verify(USER, code(now - 60)));
        answers.put("two steps ahead", totp.verify(USER, code(now + 60)));
        answers.put("one step back", totp.verify(USER, code(now - 30)));
        answers.put("one step ahead", totp.verify(USER, code(now + 30)));
        assertThat(answers)
            .containsExactly(
                    Map.entry("two steps back", false),
                    Map.entry("two steps ahead", false),
                    Map.entry("one step back", true),
                    Map.entry("one step ahead", true)
            );
    }

    @Test
    void rejectsNonDigits() {
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("letter", totp.verify(USER, "12345a"));
        answers.put("leading space", totp.verify(USER, " 123456"));
        answers.put("seven digits", totp.verify(USER, "1234567"));
        answers.put("null", totp.verify(USER, null));
        assertThat(answers)
            .containsExactly(
                    Map.entry("letter", false),
                    Map.entry("leading space", false),
                    Map.entry("seven digits", false),
                    Map.entry("null", false)
            );
    }

    @Test
    void confirmingCodeIsSpentForVerify() {
        when(factors.totp(USER)).thenReturn(row(false), row(true));
        when(factors.confirmTotp(USER, ciphertext, 1)).thenReturn(true);
        // confirming records its step, so the repository refuses it to verify
        when(factors.advanceTotp(USER, 1)).thenReturn(false);
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("confirm", totp.confirm(USER, "287082"));
        answers.put("verify", totp.verify(USER, "287082"));
        assertThat(answers).containsExactly(Map.entry("confirm", true), Map.entry("verify", false));
    }

    @Test
    void unconfirmedSeedDoesNotVerifyButConfirms() {
        when(factors.totp(USER)).thenReturn(row(false), row(false), row(true));
        when(factors.confirmTotp(USER, ciphertext, 1)).thenReturn(true);
        when(factors.advanceTotp(USER, 1)).thenReturn(false);
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("verify unconfirmed", totp.verify(USER, "287082"));
        verify(factors, never()).advanceTotp(eq(USER), anyLong());
        answers.put("confirm", totp.confirm(USER, "287082"));
        answers.put("verify confirmed", totp.verify(USER, "287082"));
        assertThat(answers)
            .containsExactly(
                    Map.entry("verify unconfirmed", false),
                    Map.entry("confirm", true),
                    Map.entry("verify confirmed", false)
            );
    }
}
