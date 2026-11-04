package site.asm0dey.stillpress.pg;

import io.quarkus.test.junit.QuarkusTestProfile;
import module java.base;

/**
 * Runs a test against PostgreSQL instead of H2. The pg config profile has no JDBC URL (application.properties), so Dev
 * Services starts the same image the jOOQ codegen uses; the jOOQ dialect follows db-kind.
 */
public class PostgresProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
        return "pg";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.db-kind",
                "postgresql",
                "quarkus.datasource.devservices.image-name",
                "postgres:18"
        );
    }
}
