package site.asm0dey.stillpress.pg;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the same PostgreSQL image the jOOQ codegen uses and points the datasource at it.
 */
public class PostgresResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer("postgres:18");
        postgres.start();
        return Map.of(
                "quarkus.datasource.jdbc.url",
                postgres.getJdbcUrl(),
                "quarkus.datasource.username",
                postgres.getUsername(),
                "quarkus.datasource.password",
                postgres.getPassword()
        );
    }

    @Override
    public void stop() {
        postgres.stop();
    }
}
