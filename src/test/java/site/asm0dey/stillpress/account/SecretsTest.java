package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.SecretRepository;

@QuarkusTest
class SecretsTest {
    @Inject
    Secrets secrets;
    @Inject
    SecretRepository repository;
    @Inject
    TestStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void roundTrip() {
        secrets.put("smtp_password", "correct horse battery staple");
        assertThat(secrets.get("smtp_password")).contains("correct horse battery staple");
        assertThat(repository.ciphertext("smtp_password"))
            .hasValueSatisfying(c -> assertThat(c).doesNotContain("correct horse"));
        assertThat(secrets.any()).isTrue();
        secrets.delete("smtp_password");
        assertThat(secrets.get("smtp_password")).isEmpty();
    }

    @Test
    void foreignCiphertextReadsAsAbsent() throws GeneralSecurityException {
        var foreign = KeysetHandle
            .generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead.class);
        var ciphertext = foreign.encrypt("value".getBytes(UTF_8), "foreign".getBytes(UTF_8));
        repository.put("foreign", Base64.getEncoder().encodeToString(ciphertext));
        assertThat(secrets.get("foreign")).isEmpty();
    }

    @Test
    void nameIsBoundToCiphertext() {
        secrets.put("a", "value");
        repository.put("b", repository.ciphertext("a").orElseThrow());
        assertThat(secrets.get("b")).isEmpty();
        assertThat(secrets.get("a")).contains("value");
    }

    @Test
    void totpCiphertextIsNameBound() {
        assertThat(secrets.decrypt("totp:2", secrets.encrypt("totp:1", "x"))).isEmpty();
        assertThat(secrets.decrypt("totp:1", secrets.encrypt("totp:1", "x"))).contains("x");
    }

    @Test
    void deriveIsStableAndPurposeBound() {
        var x = secrets.derive("x", 32);
        assertThat(x).hasSize(32).isEqualTo(secrets.derive("x", 32)).isNotEqualTo(secrets.derive("y", 32));
    }
}
