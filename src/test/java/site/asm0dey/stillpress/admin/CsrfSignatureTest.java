package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.account.Secrets;

@QuarkusTest
class CsrfSignatureTest {
    @Inject
    Secrets secrets;

    @Test
    void signatureKeyComesFromThePrfKeyset() {
        var key = ConfigProvider.getConfig().getValue("quarkus.rest-csrf.token-signature-key", String.class);
        assertThat(key).hasSize(64).isEqualTo(HexFormat.of().formatHex(secrets.derive("csrf-token-signature", 32)));
    }
}
