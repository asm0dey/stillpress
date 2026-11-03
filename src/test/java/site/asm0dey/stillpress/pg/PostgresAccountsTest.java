package site.asm0dey.stillpress.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.SESSION;
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
        assertThat(users.findByLoginOrEmail("mama")).hasValueSatisfying(u -> assertThat(u.getId())
            .isEqualTo(mama.getId()));

        assertThat(users.create("a b", "ab1@example.org", "A B", Role.ADMINISTRATOR).getSlug()).isEqualTo("a-b");
        assertThat(users.create("a-b", "ab2@example.org", "A-B", Role.ADMINISTRATOR).getSlug()).isEqualTo("a-b-2");

        var raw = sessions.create(mama.getId());
        assertThat(sessions.find(raw)).hasValueSatisfying(u -> assertThat(u.getId()).isEqualTo(mama.getId()));
        db.update(SESSION).set(SESSION.EXPIRES_AT, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).execute();
        assertThat(sessions.find(raw)).isEmpty();
        var raw2 = sessions.create(mama.getId());
        sessions.delete(raw2);
        assertThat(sessions.find(raw2)).isEmpty();

        var token = tokens.create(mama.getId(), "set_password", Duration.ofHours(1));
        assertThat(tokens.consume(token, "other")).isEmpty();
        assertThat(tokens.consume(token, "set_password")).contains(mama.getId());
        assertThat(tokens.consume(token, "set_password")).isEmpty();
        var expired = tokens.create(mama.getId(), "set_password", Duration.ofSeconds(-1));
        assertThat(tokens.consume(expired, "set_password")).isEmpty();
    }

    @Test
    void concurrentTokenConsumeHasOneWinner() throws Exception {
        var user = users.create("racer", "racer@example.org", "Racer", Role.ADMINISTRATOR);
        var token = tokens.create(user.getId(), "set_password", Duration.ofHours(1));
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
}
