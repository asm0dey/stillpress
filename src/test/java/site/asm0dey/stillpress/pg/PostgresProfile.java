package site.asm0dey.stillpress.pg;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

/**
 * Runs a test against PostgreSQL instead of H2; the jOOQ dialect follows db-kind.
 */
public class PostgresProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.datasource.db-kind", "postgresql", "quarkus.datasource.devservices.enabled", "false");
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresResource.class));
    }
}
