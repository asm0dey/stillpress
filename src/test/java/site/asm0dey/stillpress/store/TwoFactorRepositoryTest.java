package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;

@QuarkusTest
class TwoFactorRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    TwoFactorRepository factors;
    long id;

    @BeforeEach
    void reset() {
        store.reset();
        id = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).getId();
    }

    @Test
    void advanceTotpIsMonotonic() {
        factors.putTotp(id, "c");
        assertThat(factors.advanceTotp(id, 10)).isFalse();
        factors.confirmTotp(id);
        assertThat(factors.advanceTotp(id, 10)).isTrue();
        assertThat(factors.advanceTotp(id, 10)).isFalse();
        assertThat(factors.advanceTotp(id, 9)).isFalse();
        assertThat(factors.advanceTotp(id, 11)).isTrue();
        assertThat(factors.totp(id)).hasValueSatisfying(t -> assertThat(t.getLastStep()).isEqualTo(11));
    }

    @Test
    void putTotpReplacesAndUnconfirms() {
        factors.putTotp(id, "one");
        factors.confirmTotp(id);
        factors.advanceTotp(id, 5);
        factors.putTotp(id, "two");
        assertThat(factors.totp(id)).hasValueSatisfying(t -> {
            assertThat(t.getCiphertext()).isEqualTo("two");
            assertThat(t.getConfirmed()).isFalse();
            assertThat(t.getLastStep()).isZero();
        });
    }

    @Test
    void factorCountIgnoresUnconfirmedTotp() {
        factors.putTotp(id, "c");
        factors.addPasskey(id, "laptop", data("a"));
        factors.addPasskey(id, "phone", data("b"));
        assertThat(factors.factorCount(id)).isEqualTo(2);
        factors.confirmTotp(id);
        assertThat(factors.factorCount(id)).isEqualTo(3);
        assertThat(factors.hasFactor(id)).isTrue();
    }

    @Test
    void passkeysRoundTripAndAreUserScoped() {
        var other = users.create("other", "o@example.org", "Other", Role.ADMINISTRATOR).getId();
        var data = data("a");
        factors.addPasskey(id, "laptop", data);
        assertThat(factors.passkeys(other)).isEmpty();
        assertThat(factors.passkey("a")).hasValueSatisfying(p -> {
            assertThat(p.getUserId()).isEqualTo(id);
            assertThat(p.getName()).isEqualTo("laptop");
            assertThat(p.getAlgorithm()).isEqualTo(-7L);
            assertThat(p.getAaguid()).isEqualTo(data.aaguid().toString());
            assertThat(Base64.getDecoder().decode(p.getPublicKey())).isEqualTo(data.publicKey());
            assertThat(p.getSignCount()).isEqualTo(3);
            assertThat(p.getLastUsedAt()).isNull();
        });
        factors.usePasskey("a", 9);
        assertThat(factors.passkeys(id))
            .singleElement()
            .satisfies(p -> {
                assertThat(p.getSignCount()).isEqualTo(9);
                assertThat(p.getLastUsedAt()).isNotNull();
            });
        assertThat(factors.deletePasskey(other, "a")).isZero();
        assertThat(factors.deletePasskey(id, "a")).isOne();
    }

    @Test
    void clearDeletesBothKindsAndCountsThem() {
        factors.putTotp(id, "c");
        factors.addPasskey(id, "laptop", data("a"));
        assertThat(factors.clear(id)).isEqualTo(2);
        assertThat(factors.totp(id)).isEmpty();
        assertThat(factors.passkeys(id)).isEmpty();
        assertThat(factors.hasFactor(id)).isFalse();
    }

    private static RequiredPersistedData data(String credentialId) {
        return new RequiredPersistedData(
                "admin",
                credentialId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new byte[] {1, 2, 3, 4},
                -7,
                3
        );
    }
}
