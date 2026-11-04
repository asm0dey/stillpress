package site.asm0dey.stillpress.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.importer.AlreadyImportedException;
import site.asm0dey.stillpress.importer.ImportGuard;

@QuarkusTest
@TestProfile(PostgresProfile.class)
class PostgresImportGuardTest {
    @Inject
    TestStore store;
    @Inject
    ImportGuard guard;

    @Test
    void importRunsOnceAndRetriesOnlyIntoAnEmptyStore() throws Exception {
        store.reset();
        guard.begin();
        assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
        guard.failed("bad xml");
        guard.begin();
        guard.failed("bad xml again");
        store.importSample();
        assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
    }

    @Test
    void concurrentBeginAndCloseLetExactlyOneWin() throws Exception {
        for (var round = 0; round < 10; round++) {
            store.reset();
            var go = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var begin = pool.submit(() -> {
                    go.await();
                    guard.begin();
                    return null;
                });
                var close = pool.submit(() -> {
                    go.await();
                    guard.close();
                    return null;
                });
                go.countDown();
                var failures = Stream.of(begin, close).filter(f -> {
                    try {
                        f.get();
                        return false;
                    } catch (ExecutionException | InterruptedException e) {
                        return true;
                    }
                }).count();
                assertThat(failures).isEqualTo(1);
            }
        }
    }
}
