package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ImportGuardTest {
    @Inject
    ImportGuard guard;
    @Inject
    TestStore store;
    @Inject
    SettingRepository settings;

    @BeforeEach
    void clean() {
        store.reset();
    }

    @Test
    void secondBeginFails() throws Exception {
        guard.begin();
        assertThat(guard.state()).contains("running");
        assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
    }

    @Test
    void doneBlocksAnotherImport() throws Exception {
        guard.begin();
        guard.done();
        assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
    }

    @Test
    void retryAllowedAfterFailureThatLeftNothing() throws Exception {
        guard.begin();
        guard.failed("bad xml");
        assertThat(guard.state()).contains("failed");
        guard.begin();
    }

    @Test
    void claimIsCompareAndSet() {
        assertThat(settings.compareAndSet(ImportGuard.STATE, null, "running")).isTrue();
        assertThat(settings.compareAndSet(ImportGuard.STATE, null, "running")).as("already present").isFalse();
        settings.put(ImportGuard.STATE, "failed");
        assertThat(settings.compareAndSet(ImportGuard.STATE, "failed", "running")).isTrue();
        assertThat(settings.compareAndSet(ImportGuard.STATE, "failed", "running")).as("another writer won").isFalse();
        assertThat(guard.state()).contains("running");
    }

    @Test
    void refusedWhenContentExistsEvenWithoutState() throws Exception {
        store.importSample();
        settings.delete(ImportGuard.STATE);
        assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class);
        assertThat(guard.state()).isEmpty();
    }

    @Test
    void startEmptyClosesImport() throws Exception {
        guard.close();
        assertThatThrownBy(guard::begin).isInstanceOf(AlreadyImportedException.class).hasMessageContaining(
                "fresh install"
        );
    }

    @Test
    void closeDuringImportIsRefused() throws Exception {
        guard.begin();
        assertThatThrownBy(guard::close).isInstanceOf(ImportRunningException.class);
    }

    @Test
    void closeAfterFailedImportCloses() throws Exception {
        guard.begin();
        guard.failed("x");
        guard.close();
        assertThat(guard.state()).contains("closed");
    }

    @Test
    void closeIsANoOpWhenClosedOrDone() throws Exception {
        guard.close();
        guard.close();
        assertThat(guard.state()).contains("closed");
        settings.put(ImportGuard.STATE, "done");
        guard.close();
        assertThat(guard.state()).contains("done");
    }

    @Test
    void importableOnlyWhileFreshOrFailedAndEmpty() throws Exception {
        assertThat(guard.importable()).isTrue();
        guard.close();
        assertThat(guard.importable()).isFalse();
    }
}
