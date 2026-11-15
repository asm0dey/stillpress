package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import javax.crypto.spec.SecretKeySpec;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * Authenticator-app codes: 30 s steps, 6 digits, HMAC-SHA1, a 20-byte seed kept encrypted. A step is accepted once:
 * the repository only advances {@code last_step}, so a replayed or raced code fails.
 */
@ApplicationScoped
public class Totp {
    static final Duration STEP = Duration.ofSeconds(30);
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private final Clock clock;
    private final Secrets secrets;
    private final TwoFactorRepository factors;
    private final SettingRepository settings;
    private final TimeBasedOneTimePasswordGenerator generator;
    private final SecureRandom random = new SecureRandom();

    public record Enrollment(String secret, String uri, String qrSvg) {}

    @Inject
    public Totp(Clock clock, Secrets secrets, TwoFactorRepository factors, SettingRepository settings) {
        this.clock = clock;
        this.secrets = secrets;
        this.factors = factors;
        this.settings = settings;
        try {
            this.generator = new TimeBasedOneTimePasswordGenerator(STEP, 6, "HmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stores a fresh unconfirmed seed, replacing any earlier unconfirmed one; refuses (409) when a confirmed factor exists.
     */
    public Enrollment start(long userId, String login) {
        var seed = new byte[20];
        random.nextBytes(seed);
        var ciphertext = secrets.encrypt(name(userId), Base64.getEncoder().encodeToString(seed));
        if (!factors.putTotp(userId, ciphertext)) {
            throw new ItemService.Refused(409, "totp_exists", null);
        }
        var secret = Base32.encode(seed);
        var issuer = encode(settings
            .get("title")
            .filter(t -> !t.isBlank())
            .orElse("Stillpress"));
        var uri = "otpauth://totp/"
                + issuer
                + ":"
                + encode(login)
                + "?secret="
                + secret
                + "&issuer="
                + issuer
                + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, uri, Qr.svg(uri));
    }

    /**
     * Checks {@code code} against the unconfirmed seed; on a match confirms the factor and spends the step.
     */
    public boolean confirm(long userId, String code) {
        var row = factors.totp(userId).filter(t -> !t.getConfirmed());
        if (row.isEmpty() || !CODE.matcher(code == null ? "" : code).matches()) {
            return false;
        }
        var ciphertext = row.get().getCiphertext();
        var seed = seed(userId, ciphertext);
        var now = currentStep();
        for (long step = now - 1; seed.isPresent() && step <= now + 1; step++) {
            if (matches(seed.get(), step, code)) {
                return factors.confirmTotp(userId, ciphertext, step);
            }
        }
        return false;
    }

    /**
     * A confirmed seed only. True for the first match among the previous, current and next step whose step is newer
     * than any used before.
     */
    public boolean verify(long userId, String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return false;
        }
        var row = factors.totp(userId).filter(t -> t.getConfirmed());
        var seed = row.flatMap(t -> seed(userId, t.getCiphertext()));
        var now = currentStep();
        for (long step = now - 1; seed.isPresent() && step <= now + 1; step++) {
            if (matches(seed.get(), step, code) && factors.advanceTotp(userId, step)) {
                return true;
            }
        }
        return false;
    }

    private long currentStep() {
        return clock.instant().getEpochSecond() / STEP.toSeconds();
    }

    private Optional<byte[]> seed(long userId, String ciphertext) {
        return secrets
            .decrypt(name(userId), ciphertext)
            .map(s -> Base64.getDecoder().decode(s));
    }

    private boolean matches(byte[] seed, long step, String code) {
        try {
            var expected = generator.generateOneTimePasswordString(
                    new SecretKeySpec(seed, "HmacSHA1"),
                    Instant.ofEpochSecond(step * STEP.toSeconds())
            );
            return MessageDigest.isEqual(expected.getBytes(UTF_8), code.getBytes(UTF_8));
        } catch (InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String name(long userId) {
        return "totp:" + userId;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, UTF_8).replace("+", "%20");
    }
}
