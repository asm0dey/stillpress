package site.asm0dey.stillpress.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.jooq.runtime.DslContextFactory;
import io.quarkiverse.jooq.runtime.JooqCustomContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;

/** quarkus-jooq maps the quarkus.datasource.db-kind values we interpolate into quarkus.jooq.dialect. */
class DialectTest {
    private static SQLDialect dialectFor(String dbKind) {
        return DslContextFactory.create(dbKind, null, new JooqCustomContext() {}).configuration().dialect();
    }

    @Test
    void dbKindMapsToDialect() {
        assertThat(dialectFor("h2")).isEqualTo(SQLDialect.H2);
        assertThat(dialectFor("postgresql")).isEqualTo(SQLDialect.POSTGRES);
    }
}
