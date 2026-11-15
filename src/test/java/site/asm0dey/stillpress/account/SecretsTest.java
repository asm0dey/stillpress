package site.asm0dey.stillpress.account;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.store.SecretRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@ExtendWith(MockitoExtension.class)
class SecretsTest {
    @TempDir
    Path root;
    @Mock
    SecretRepository repository;
    @Mock
    TwoFactorRepository totp;
    Secrets secrets;

    @BeforeEach
    void setUp() throws GeneralSecurityException {
        // real crypto: Keys generates the keysets under the temporary data dir
        secrets = new Secrets(repository, totp, new DataDir(root.toString()), Optional.empty());
    }

    private String stored(String name, String value) {
        secrets.put(name, value);
        var ciphertext = ArgumentCaptor.forClass(String.class);
        verify(repository).put(eq(name), ciphertext.capture());
        return ciphertext.getValue();
    }

    @Test
    void roundTrip() {
        var ciphertext = stored("smtp_password", "correct horse battery staple");
        assertThat(ciphertext).doesNotContain("correct horse");
        when(repository.ciphertext("smtp_password")).thenReturn(Optional.of(ciphertext));
        assertThat(secrets.get("smtp_password")).contains("correct horse battery staple");
        when(repository.any()).thenReturn(true);
        assertThat(Map.of("any", secrets.any())).containsExactly(Map.entry("any", true));
        secrets.delete("smtp_password");
        verify(repository).delete("smtp_password");
        when(repository.ciphertext("smtp_password")).thenReturn(Optional.empty());
        assertThat(secrets.get("smtp_password")).isEmpty();
    }

    @Test
    void foreignCiphertextReadsAsAbsent() throws GeneralSecurityException {
        var foreign = KeysetHandle
            .generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead.class);
        var ciphertext = foreign.encrypt("value".getBytes(UTF_8), "foreign".getBytes(UTF_8));
        when(repository.ciphertext("foreign")).thenReturn(Optional.of(Base64.getEncoder().encodeToString(ciphertext)));
        assertThat(secrets.get("foreign")).isEmpty();
    }

    @Test
    void nameIsBoundToCiphertext() {
        var ciphertext = stored("a", "value");
        when(repository.ciphertext("a")).thenReturn(Optional.of(ciphertext));
        when(repository.ciphertext("b")).thenReturn(Optional.of(ciphertext));
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
