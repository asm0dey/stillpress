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

/**
 * Named values stored encrypted under the AEAD keyset, with the name as associated data, and keys derived from the
 * PRF keyset.
 */
@ApplicationScoped
public class Secrets {
    private static final Logger LOG = Logger.getLogger(Secrets.class);
    private final SecretRepository repository;
    private final Aead aead;
    private final PrfSet prf;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Secrets(
            SecretRepository repository,
            DataDir dataDir,
            @ConfigProperty(name = "stillpress.keys-dir") Optional<Path> keysDir
    ) throws GeneralSecurityException {
        this.repository = repository;
        var keys = Keys.get(dataDir.root(), keysDir);
        this.aead = keys.aead().getPrimitive(RegistryConfiguration.get(), Aead.class);
        this.prf = keys.prf().getPrimitive(RegistryConfiguration.get(), PrfSet.class);
    }

    /**
     * Empty when absent, and when the value does not decrypt (another keyset, or a row copied under another name).
     */
    public Optional<String> get(String name) {
        return repository.ciphertext(name).flatMap(c -> {
            try {
                return Optional.of(new String(aead.decrypt(Base64.getDecoder().decode(c), name.getBytes(UTF_8)), UTF_8));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                if (warned.add(name)) {
                    LOG.warnf("Stored secret %s does not decrypt with the current keyset; treating it as unset", name);
                }
                return Optional.empty();
            }
        });
    }

    public void put(String name, String value) {
        try {
            var ciphertext = aead.encrypt(value.getBytes(UTF_8), name.getBytes(UTF_8));
            repository.put(name, Base64.getEncoder().encodeToString(ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        warned.remove(name);
    }

    public void delete(String name) {
        repository.delete(name);
    }

    public boolean any() {
        return repository.any();
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
