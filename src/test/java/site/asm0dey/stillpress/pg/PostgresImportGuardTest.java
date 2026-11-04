package site.asm0dey.stillpress.pg;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
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
}
