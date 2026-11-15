package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;

@QuarkusTest
class TokenRepositoryTest {
    @Inject
    TestStore store;
    @Inject
    AuthorRepository users;
    @Inject
    TokenRepository tokens;
    @Inject
    DSLContext db;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void tokensAreSingleUseAndPurposeBound() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raw = tokens.create(u.getId(), "set_password", Duration.ofHours(24));
        assertThat(tokens.consume(raw, "other")).isEmpty();
        assertThat(tokens.consume(raw, "set_password")).contains(u.getId());
        assertThat(tokens.consume(raw, "set_password")).isEmpty();
        var expired = tokens.create(u.getId(), "set_password", Duration.ofSeconds(-1));
        assertThat(tokens.consume(expired, "set_password")).isEmpty();
        assertThat(db.fetchCount(USER_TOKEN)).isZero();
        assertThat(tokens.consume("unknown", "set_password")).isEmpty();
    }

    @Test
    void concurrentCreatesLeaveOneToken() throws Exception {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var raws = race(() -> tokens.create(u.getId(), "set_password", Duration.ofHours(1)));
        assertThat(db.fetchCount(USER_TOKEN)).isEqualTo(1);
        var consumed = raws
            .stream()
            .map(r -> tokens.consume(r, "set_password"))
            .filter(Optional::isPresent)
            .count();
        assertThat(consumed).isEqualTo(1);
    }

    @Test
    void throttleIsAtomic() throws Exception {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var since = Instant.now().minus(Duration.ofMinutes(5));
        var issued = race(() -> tokens.createUnlessIssuedSince(u.getId(), "set_password", Duration.ofHours(1), since));
        assertThat(issued.stream().filter(Optional::isPresent).count()).isEqualTo(1);
    }

    @Test
    void anOlderTokenIsReplacedAndANewerOneKept() {
        var u = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        var first = tokens.create(u.getId(), "set_password", Duration.ofHours(1));
        assertThat(tokens.createUnlessIssuedSince(
                u.getId(),
                "set_password",
                Duration.ofHours(1),
                Instant.now().minus(Duration.ofMinutes(5))
        ))
            .isEmpty();
        var second = tokens.createUnlessIssuedSince(
                u.getId(),
                "set_password",
                Duration.ofHours(1),
                Instant.now().plus(Duration.ofMinutes(1))
        );
        assertThat(second).isPresent();
        assertThat(tokens.consume(first, "set_password")).isEmpty();
        assertThat(tokens.consume(second.get(), "set_password")).contains(u.getId());
    }

    private static <T> List<T> race(Callable<T> call) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = IntStream
                .range(0, 2)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return call.call();
                }))
                .toList();
            start.countDown();
            var out = new ArrayList<T>();
            for (var f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}
