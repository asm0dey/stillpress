package site.asm0dey.stillpress.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.SESSION;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@QuarkusTest
@TestProfile(PostgresProfile.class)
class PostgresAccountsTest {
    @Inject
    TestStore store;
    @Inject
    DSLContext db;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    TokenRepository tokens;
    @Inject
    TwoFactorRepository factors;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void dialectIsPostgres() {
        assertThat(db.dialect()).isEqualTo(SQLDialect.POSTGRES);
    }

    @Test
    void accountLifecycle() {
        var mama = users.create("mama", "mama@example.org", "Mama", Role.ADMINISTRATOR);
        users.create("papa", "mama", "Papa", Role.ADMINISTRATOR);
        assertThat(users.findByLoginOrEmail("mama")).hasValueSatisfying(u -> assertThat(u.id()).isEqualTo(mama.id()));

        assertThat(users.create("a b", "ab1@example.org", "A B", Role.ADMINISTRATOR).slug()).isEqualTo("a-b");
        assertThat(users.create("a-b", "ab2@example.org", "A-B", Role.ADMINISTRATOR).slug()).isEqualTo("a-b-2");

        var raw = sessions.create(mama.id());
        assertThat(sessions.find(raw)).hasValueSatisfying(u -> assertThat(u.id()).isEqualTo(mama.id()));
        db.update(SESSION).set(SESSION.EXPIRES_AT, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).execute();
        assertThat(sessions.find(raw)).isEmpty();
        var raw2 = sessions.create(mama.id());
        sessions.delete(raw2);
        assertThat(sessions.find(raw2)).isEmpty();
        var a = sessions.create(mama.id());
        var b = sessions.create(mama.id());
        sessions.deleteAllFor(mama.id());
        assertThat(sessions.find(a)).isEmpty();
        assertThat(sessions.find(b)).isEmpty();

        var token = tokens.create(mama.id(), "set_password", Duration.ofHours(1));
        assertThat(tokens.consume(token, "other")).isEmpty();
        assertThat(tokens.consume(token, "set_password")).contains(mama.id());
        assertThat(tokens.consume(token, "set_password")).isEmpty();
        var expired = tokens.create(mama.id(), "set_password", Duration.ofSeconds(-1));
        assertThat(tokens.consume(expired, "set_password")).isEmpty();
    }

    @Test
    void concurrentCreatesSameEmailDifferentCaseHaveOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var emails = List.of("A@x.org", "a@x.org");
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                var n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        users.create("mail" + n, emails.get(n), "M", Role.AUTHOR);
                        return true;
                    } catch (AuthorRepository.Taken e) {
                        assertThat(e.email).as(e.getMessage()).isTrue();
                        return false;
                    }
                }));
            }
            start.countDown();
            var outcomes = new ArrayList<Boolean>();
            for (var f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void concurrentTokenConsumeHasOneWinner() throws Exception {
        var user = users.create("racer", "racer@example.org", "Racer", Role.ADMINISTRATOR);
        var token = tokens.create(user.id(), "set_password", Duration.ofHours(1));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Optional<Long>>>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tokens.consume(token, "set_password");
                }));
            }
            start.countDown();
            var winners = 0;
            for (var f : results) {
                if (f.get().isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void twoFactorSql() {
        var totpUser = users.create("totp", "totp@example.org", "Totp", Role.ADMINISTRATOR).id();
        var keyUser = users.create("key", "key@example.org", "Key", Role.AUTHOR).id();
        var none = users.create("none", "none@example.org", "None", Role.AUTHOR).id();

        assertThat(factors.putTotp(totpUser, "first")).isTrue();
        assertThat(factors.putTotp(totpUser, "second")).isTrue();
        assertThat(factors.confirmTotp(totpUser, "first", 10)).isFalse();
        assertThat(factors.confirmTotp(totpUser, "second", 10)).isTrue();
        assertThat(factors.putTotp(totpUser, "third")).isFalse();
        assertThat(factors.advanceTotp(totpUser, 10)).isFalse();
        assertThat(factors.advanceTotp(totpUser, 11)).isTrue();
        assertThat(factors.totp(totpUser)).hasValueSatisfying(t -> assertThat(t.lastStep()).isEqualTo(11L));

        factors.addPasskey(
                keyUser,
                "key",
                new RequiredPersistedData(
                        "key",
                        "credential-1",
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new byte[] {1, 2, 3, 4},
                        -7,
                        0
                )
        );
        assertThat(factors.factorCount(keyUser)).isEqualTo(1);
        assertThat(factors.factorCount(totpUser)).isEqualTo(1);
        assertThat(factors.factorCount(none)).isZero();
        assertThat(factors.hasFactor(keyUser)).isTrue();
        assertThat(factors.hasFactor(none)).isFalse();

        assertThat(users
            .allWithTwoFactor()
            .stream()
            .collect(Collectors.toMap(r -> r.author().login(), r -> r.twoFactor()))
        )
            .containsExactlyInAnyOrderEntriesOf(Map.of("totp", true, "key", true, "none", false));

        var a = sessions.create(totpUser);
        var b = sessions.create(keyUser);
        var c = sessions.create(none);
        sessions.deleteAllWithoutSecondFactor();
        assertThat(sessions.find(a)).isPresent();
        assertThat(sessions.find(b)).isPresent();
        assertThat(sessions.find(c)).isEmpty();

        var since = Instant.now().minus(Duration.ofMinutes(5));
        assertThat(tokens.createUnlessIssuedSince(none, "set_password", Duration.ofHours(1), since)).isPresent();
        assertThat(tokens.createUnlessIssuedSince(none, "set_password", Duration.ofHours(1), since)).isEmpty();
    }
}
