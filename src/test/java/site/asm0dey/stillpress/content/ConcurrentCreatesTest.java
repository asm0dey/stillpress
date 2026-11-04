package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

@QuarkusTest
class ConcurrentCreatesTest {
    static final int WRITERS = 16;
    @Inject
    TestStore store;
    @Inject
    ItemService items;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    AuthorRepository users;

    @Test
    void sixteenConcurrentItemCreatesGetDistinctIds() throws Exception {
        assertDistinct(author -> items.create("post", author).getSourceId());
    }

    @Test
    void sixteenConcurrentBlockCreatesGetDistinctIds() throws Exception {
        assertDistinct(author -> blocks
            .create("Block", "<!-- wp:paragraph -->", author, LocalDateTime.now())
            .getSourceId());
    }

    @Test
    void anUnknownAuthorFailsWithTheForeignKeyErrorNotAPublicIdError() {
        store.reset();
        assertThatThrownBy(() -> items.create("post", 987654L))
            .isInstanceOf(DataAccessException.class)
            .hasMessageNotContaining("No free public id");
    }

    private void assertDistinct(LongFunction<Long> create) throws Exception {
        for (var round = 0; round < 5; round++) {
            store.reset();
            var author = users.create("writer", "writer@example.org", "Writer", Role.AUTHOR).getId();
            var start = new CyclicBarrier(WRITERS);
            try (var pool = Executors.newFixedThreadPool(WRITERS)) {
                var creates = IntStream
                    .range(0, WRITERS)
                    .mapToObj(i -> pool.submit(() -> QuarkusTransaction.requiringNew().call(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return create.apply(author);
                    })))
                    .toList();
                var ids = new ArrayList<Long>();
                for (var c : creates) {
                    ids.add(c.get(60, TimeUnit.SECONDS));
                }
                assertThat(ids).doesNotHaveDuplicates().hasSize(WRITERS);
            }
        }
    }
}
