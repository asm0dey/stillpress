package site.asm0dey.stillpress.account;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.i18n.Localized;
import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import module java.net.http;
import org.altcha.altcha.v1.Altcha;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.stillpress.content.ItemService.Refused;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The CAPTCHA on the public admin forms: {@code none}, Cloudflare {@code turnstile} or self-hosted {@code altcha}, plus
 * a honeypot field checked under every provider. The provider is a site setting unless the environment sets it, which
 * locks it.
 */
@ApplicationScoped
public class Captcha {
    public static final Set<String> PROVIDERS = Set.of("none", "turnstile", "altcha");
    static final String SECRET = "turnstile_secret";
    // answered by Cloudflare as a bad response, not a bad secret: enough to tell whether the secret is real
    private static final String KEY_CHECK = "stillpress-key-check";
    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*true");
    private static final Pattern BAD_SECRET =
            Pattern.compile("\"error-codes\"\\s*:\\s*\\[[^\\]]*\"invalid-input-secret\"");
    // bound the synchronous call so a hung upstream can't pin a request thread
    private final HttpClient http = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final Optional<String> envProvider;
    private final Optional<String> envSiteKey;
    private final Optional<String> envSecret;
    private final URI verifyUrl;
    private final Duration timeout;
    private final SettingRepository settings;
    private final Secrets secrets;

    public Captcha(
            @ConfigProperty(name = "stillpress.captcha.provider") Optional<String> envProvider,
            @ConfigProperty(name = "stillpress.captcha.turnstile.site-key") Optional<String> envSiteKey,
            @ConfigProperty(name = "stillpress.captcha.turnstile.secret") Optional<String> envSecret,
            @ConfigProperty(name = "stillpress.captcha.turnstile.verify-url") URI verifyUrl,
            @ConfigProperty(name = "stillpress.captcha.turnstile.timeout") Duration timeout,
            SettingRepository settings,
            Secrets secrets
    ) {
        this.envProvider = envProvider.map(p -> p.strip().toLowerCase(Locale.ROOT));
        this.envSiteKey = envSiteKey;
        this.envSecret = envSecret;
        this.verifyUrl = verifyUrl;
        this.timeout = timeout;
        this.settings = settings;
        this.secrets = secrets;
    }

    /**
     * What is wrong with the environment, for `serve` to refuse at startup; the constructor itself never throws, so
     * `captcha none` can still start and print its override warning.
     */
    public Optional<String> environmentProblem() {
        var unknown = envProvider.filter(p -> !PROVIDERS.contains(p));
        if (unknown.isPresent()) {
            return Optional.of("STILLPRESS_CAPTCHA_PROVIDER must be none, turnstile or altcha: " + unknown.get());
        }
        if (envProvider.filter("turnstile"::equals).isPresent() && (isBlank(envSiteKey) || isBlank(envSecret))) {
            return Optional.of(
                    "STILLPRESS_CAPTCHA_PROVIDER is turnstile, but STILLPRESS_CAPTCHA_TURNSTILE_SITE_KEY and"
                    + " STILLPRESS_CAPTCHA_TURNSTILE_SECRET must both be set"
            );
        }
        return Optional.empty();
    }

    private static boolean isBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank()).isEmpty();
    }

    public String provider() {
        return envProvider
            .or(() -> settings.get("captcha_provider"))
            .orElse("none");
    }

    public boolean locked() {
        return envProvider.isPresent();
    }

    public Optional<String> turnstileSiteKey() {
        return locked() ? envSiteKey : settings.get("turnstile_site_key");
    }

    public boolean turnstileSecretSet() {
        return turnstileSecret().isPresent();
    }

    private Optional<String> turnstileSecret() {
        return locked() ? envSecret : secrets.get(SECRET);
    }

    public String hmacKey() {
        return HexFormat.of().formatHex(secrets.derive("altcha-hmac", 32));
    }

    /**
     * A signed proof-of-work challenge valid for five minutes. Verification is stateless, so a solved payload can be
     * replayed until it expires; the honeypot and the rate limits are the backstops.
     */
    public Altcha.Challenge challenge() {
        try {
            return Altcha.createChallenge(new Altcha.ChallengeOptions()
                .algorithm(Altcha.Algorithm.SHA256)
                .maxNumber(100_000)
                .hmacKey(hmacKey())
                .expiresInSeconds(300)
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Throws {@link CaptchaFailedException} when the honeypot is filled or the active provider refuses the token.
     */
    public void verify(String token, String honeypot, String ip) {
        if (honeypot != null && !honeypot.isEmpty()) {
            throw new CaptchaFailedException("honeypot filled");
        }
        var provider = provider();
        if (provider.equals("none")) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new CaptchaFailedException("missing CAPTCHA token");
        }
        boolean ok;
        try {
            ok = provider.equals("altcha")
                    ? Altcha.verifySolution(token, hmacKey(), true)
                    : SUCCESS.matcher(siteverify(turnstileSecret().orElse(""), token, ip)).find();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        } catch (Exception e) {
            // a malformed payload or an unreachable Cloudflare fails closed
            ok = false;
        }
        if (!ok) {
            throw new CaptchaFailedException(provider + " verification failed");
        }
    }

    /**
     * Saves the provider, checking a Turnstile secret with Cloudflare first; {@code secret} {@code null} keeps the
     * stored one.
     */
    public void save(String provider, String siteKey, String secret) {
        if (locked()) {
            throw new Refused(409, "captcha_locked", null);
        }
        if (provider == null || !PROVIDERS.contains(provider)) {
            throw new Refused(400, "invalid", "provider");
        }
        var key = siteKey == null ? "" : siteKey.strip();
        var newSecret = secret == null ? secrets.get(SECRET).orElse("") : secret.strip();
        if (provider.equals("turnstile")) {
            if (key.isEmpty()) {
                throw new Refused(400, "invalid", "turnstileSiteKey");
            }
            if (newSecret.isEmpty()) {
                throw new Refused(400, "invalid", "turnstileSecret");
            }
            String answer;
            try {
                answer = siteverify(newSecret, KEY_CHECK, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Refused(502, "turnstile_unreachable", null);
            } catch (IOException e) {
                throw new Refused(502, "turnstile_unreachable", null);
            }
            if (BAD_SECRET.matcher(answer).find()) {
                throw new Refused(400, "invalid_turnstile_secret", null);
            }
        }
        QuarkusTransaction.requiringNew().run(() -> {
            settings.put("captcha_provider", provider);
            if (provider.equals("turnstile")) {
                settings.put("turnstile_site_key", key);
                if (secret != null) {
                    secrets.put(SECRET, newSecret);
                }
            }
        });
    }

    /**
     * Sets the provider to {@code none} without any check, for the CLI; the environment still wins when set.
     */
    public void turnOff() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> settings.put("captcha_provider", "none"));
    }

    /**
     * Cloudflare's answer; it sends a bad secret with a 400, and anything but a 200 or 400 counts as unreachable.
     */
    private String siteverify(String secret, String response, String ip) throws IOException, InterruptedException {
        var body = "secret="
                + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&response="
                + URLEncoder.encode(response, StandardCharsets.UTF_8)
                + (ip == null ? "" : "&remoteip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8));
        var req = HttpRequest
            .newBuilder(verifyUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 400) {
            throw new IOException("siteverify answered " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * The sentence the public forms show about the active provider; empty for {@code none}.
     */
    public String privacyNotice(String locale) {
        var texts = MessageBundles.get(Texts.class, Localized.Literal.of("ru".equals(locale) ? "ru" : "en"));
        return switch (provider()) {
            case "turnstile" -> texts.turnstile();
            case "altcha" -> texts.altcha();
            default -> "";
        };
    }

    /**
     * English; Russian in {@code messages/captcha_ru.properties}.
     */
    @MessageBundle(value = "captcha", locale = "en")
    public interface Texts {
        @Message(
                "This form is protected by Cloudflare Turnstile. Cloudflare processes your IP address and browser"
                + " data to check that you are not a bot."
        )
        String turnstile();

        @Message(
                "This form is protected by ALTCHA, a proof-of-work check that runs in your browser and is verified by"
                + " this site. No data is sent to a third party."
        )
        String altcha();
    }
}
