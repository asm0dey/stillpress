package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.tables.Setting.SETTING;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;

@QuarkusTest
class SchemaTest {
    @Inject
    DSLContext db;
    @Inject
    TestStore store;

    @BeforeEach
    void clean() {
        store.reset();
    }

    @Test
    void migrationsCreateCoreTables() {
        db.insertInto(SETTING, SETTING.KEY, SETTING.VALUE).values("title", "Блог").execute();
        assertThat(db.fetchValue(SETTING.VALUE, SETTING.KEY.eq("title"))).isEqualTo("Блог");
    }
}
