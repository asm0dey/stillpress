package site.asm0dey.stillpress.publish;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;

@QuarkusTest
class PublishLockTest {
    @Inject
    PublishLock lock;
    @Inject
    TestStore store;

    @BeforeEach
    void clean() {
        store.reset();
    }

    @Test
    void secondHolderWaitsForTheFirst() throws Exception {
        var aEnd = new AtomicReference<Instant>();
        var bStart = new AtomicReference<Instant>();
        var aHolding = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> lock.holding(() -> {
                aHolding.countDown();
                sleep(500);
                aEnd.set(Instant.now());
                return null;
            }));
            aHolding.await();
            var b = pool.submit(() -> lock.holding(() -> {
                bStart.set(Instant.now());
                return null;
            }));
            a.get();
            b.get();
        }
        assertThat(bStart.get()).isAfterOrEqualTo(aEnd.get());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
