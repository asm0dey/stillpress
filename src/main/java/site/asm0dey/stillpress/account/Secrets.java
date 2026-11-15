package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.prf.PrfSet;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.config.Keys;
import site.asm0dey.stillpress.store.SecretRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * Named values stored encrypted under the AEAD keyset, with the name as associated data, and keys derived from the
 * PRF keyset.
 */
@ApplicationScoped
public class Secrets {
    private static final Logger LOG = Logger.getLogger(Secrets.class);
    private final SecretRepository repository;
    private final TwoFactorRepository totp;
    private final Aead aead;
    private final PrfSet prf;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Secrets(
            SecretRepository repository,
            TwoFactorRepository totp,
            DataDir dataDir,
            @ConfigProperty(name = "stillpress.keys-dir") Optional<Path> keysDir
    ) throws GeneralSecurityException {
        this.repository = repository;
        this.totp = totp;
        var keys = Keys.get(dataDir.root(), keysDir);
        this.aead = aead(keys);
        this.prf = keys.prf().getPrimitive(RegistryConfiguration.get(), PrfSet.class);
    }

    /**
     * Empty when absent, and when the value does not decrypt (another keyset, or a row copied under another name).
     */
    public Optional<String> get(String name) {
        return repository.ciphertext(name).flatMap(c -> {
            var value = decrypt(aead, name, c);
            if (value.isEmpty() && warned.add(name)) {
                LOG.warnf("Stored secret %s does not decrypt with the current keyset; treating it as unset", name);
            }
            return value;
        });
    }

    /**
     * True when nothing is stored or a stored secret or TOTP seed decrypts under {@code keys}: false means they belong
     * to another keyset.
     */
    public boolean readableWith(Keys keys) {
        var aead = aead(keys);
        return repository
            .first()
            .map(r -> decrypt(aead, r.getName(), r.getCiphertext()).isPresent())
            .or(() -> totp
                .firstTotp()
                .map(t -> decrypt(aead, totpName(t.getUserId()), t.getCiphertext()).isPresent()))
            .orElse(true);
    }

    private static String totpName(long userId) {
        return "totp:" + userId;
    }

    private static Aead aead(Keys keys) {
        try {
            return keys.aead().getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Empty when {@code ciphertext} does not decrypt for {@code name}.
     */
    public Optional<String> decrypt(String name, String ciphertext) {
        return decrypt(aead, name, ciphertext);
    }

    private static Optional<String> decrypt(Aead aead, String name, String ciphertext) {
        try {
            return Optional.of(
                    new String(aead.decrypt(Base64.getDecoder().decode(ciphertext), name.getBytes(UTF_8)), UTF_8)
            );
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Base64 AEAD output with {@code name} as associated data.
     */
    public String encrypt(String name, String value) {
        try {
            return Base64.getEncoder().encodeToString(aead.encrypt(value.getBytes(UTF_8), name.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void put(String name, String value) {
        repository.put(name, encrypt(name, value));
        warned.remove(name);
    }

    public void delete(String name) {
        repository.delete(name);
    }

    public boolean any() {
        return repository.any() || totp.firstTotp().isPresent();
    }

    /**
     * A {@code length}-byte key for {@code purpose}, stable for the life of the keyset.
     */
    public byte[] derive(String purpose, int length) {
        try {
            return prf.computePrimary(purpose.getBytes(UTF_8), length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
