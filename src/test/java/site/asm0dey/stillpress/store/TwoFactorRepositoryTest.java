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
import site.asm0dey.stillpress.db.tables.pojos.TotpFactor;

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
        id = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).id();
    }

    @Test
    void advanceTotpIsMonotonic() {
        factors.putTotp(id, "c");
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("unconfirmed", factors.advanceTotp(id, 10));
        factors.confirmTotp(id, "c", 0);
        answers.put("first 10", factors.advanceTotp(id, 10));
        answers.put("repeat 10", factors.advanceTotp(id, 10));
        answers.put("back to 9", factors.advanceTotp(id, 9));
        answers.put("forward to 11", factors.advanceTotp(id, 11));
        assertThat(answers)
            .containsExactly(
                    Map.entry("unconfirmed", false),
                    Map.entry("first 10", true),
                    Map.entry("repeat 10", false),
                    Map.entry("back to 9", false),
                    Map.entry("forward to 11", true)
            );
        assertThat(factors.totp(id)).hasValueSatisfying(t -> assertThat(t.lastStep()).isEqualTo(11));
    }

    @Test
    void confirmingAStepSpendsItForAdvance() {
        factors.putTotp(id, "c");
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("confirm at 7", factors.confirmTotp(id, "c", 7));
        answers.put("advance to 7", factors.advanceTotp(id, 7));
        answers.put("advance to 8", factors.advanceTotp(id, 8));
        assertThat(answers)
            .containsExactly(
                    Map.entry("confirm at 7", true),
                    Map.entry("advance to 7", false),
                    Map.entry("advance to 8", true)
            );
    }

    @Test
    void concurrentAdvanceOfOneStepSucceedsOnce() throws Exception {
        factors.putTotp(id, "c");
        factors.confirmTotp(id, "c", 0);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = IntStream
                .range(0, 8)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return factors.advanceTotp(id, 1);
                }))
                .toList();
            start.countDown();
            var wins = 0;
            for (var r : results) {
                wins += r.get() ? 1 : 0;
            }
            assertThat(wins).isEqualTo(1);
        }
    }

    @Test
    void putTotpReplacesAndUnconfirms() {
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("first put", factors.putTotp(id, "one"));
        answers.put("second put", factors.putTotp(id, "two"));
        assertThat(answers).containsExactly(Map.entry("first put", true), Map.entry("second put", true));
        assertThat(factors.totp(id))
            .hasValueSatisfying(t -> assertThat(t)
                .extracting(TotpFactor::ciphertext, TotpFactor::confirmed, TotpFactor::lastStep)
                .containsExactly("two", false, 0L));
    }

    @Test
    void concurrentFirstPutsNeitherFailNorDuplicate() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = IntStream
                .range(0, 2)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return factors.putTotp(id, "c" + i);
                }))
                .toList();
            start.countDown();
            var results = new ArrayList<Boolean>();
            for (var f : futures) {
                results.add(f.get());
            }
            assertThat(results).containsExactly(true, true);
        }
        assertThat(factors.totp(id))
            .hasValueSatisfying(t -> assertThat(t)
                .extracting(TotpFactor::confirmed, TotpFactor::lastStep)
                .containsExactly(false, 0L));
        assertThat(factors.firstTotp()).isPresent();
    }

    @Test
    void putTotpNeverReplacesAConfirmedFactor() {
        factors.putTotp(id, "one");
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("confirm", factors.confirmTotp(id, "one", 5));
        answers.put("replace", factors.putTotp(id, "two"));
        assertThat(answers).containsExactly(Map.entry("confirm", true), Map.entry("replace", false));
        assertThat(factors.totp(id))
            .hasValueSatisfying(t -> assertThat(t)
                .extracting(TotpFactor::ciphertext, TotpFactor::confirmed, TotpFactor::lastStep)
                .containsExactly("one", true, 5L));
    }

    @Test
    void confirmIsBoundToTheVerifiedCiphertext() {
        factors.putTotp(id, "two");
        var answers = new LinkedHashMap<String, Boolean>();
        answers.put("wrong ciphertext", factors.confirmTotp(id, "one", 5));
        assertThat(factors.totp(id))
            .hasValueSatisfying(t -> assertThat(t)
                .extracting(TotpFactor::confirmed, TotpFactor::lastStep)
                .containsExactly(false, 0L));
        answers.put("right ciphertext", factors.confirmTotp(id, "two", 5));
        answers.put("confirmed already", factors.confirmTotp(id, "two", 6));
        assertThat(answers)
            .containsExactly(
                    Map.entry("wrong ciphertext", false),
                    Map.entry("right ciphertext", true),
                    Map.entry("confirmed already", false)
            );
    }

    @Test
    void factorCountIgnoresUnconfirmedTotp() {
        factors.putTotp(id, "c");
        factors.addPasskey(id, "laptop", data("a"));
        factors.addPasskey(id, "phone", data("b"));
        assertThat(factors.factorCount(id)).isEqualTo(2);
        factors.confirmTotp(id, "c", 0);
        assertThat(factors.factorCount(id)).isEqualTo(3);
        assertThat(Map.of("hasFactor", factors.hasFactor(id))).containsExactly(Map.entry("hasFactor", true));
    }

    @Test
    void passkeysRoundTripAndAreUserScoped() {
        var other = users.create("other", "o@example.org", "Other", Role.ADMINISTRATOR).id();
        var data = data("a");
        factors.addPasskey(id, "laptop", data);
        assertThat(factors.passkeys(other)).isEmpty();
        assertThat(factors.passkey("a")).hasValueSatisfying(p -> {
            assertThat(p.userId()).isEqualTo(id);
            assertThat(p.name()).isEqualTo("laptop");
            assertThat(p.algorithm()).isEqualTo(-7L);
            assertThat(p.aaguid()).isEqualTo(data.aaguid().toString());
            assertThat(Base64.getDecoder().decode(p.publicKey())).isEqualTo(data.publicKey());
            assertThat(p.signCount()).isEqualTo(3);
            assertThat(p.lastUsedAt()).isNull();
        });
        factors.usePasskey("a", 9);
        assertThat(factors.passkeys(id))
            .singleElement()
            .satisfies(p -> {
                assertThat(p.signCount()).isEqualTo(9);
                assertThat(p.lastUsedAt()).isNotNull();
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
        assertThat(Map.of("hasFactor", factors.hasFactor(id))).containsExactly(Map.entry("hasFactor", false));
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
