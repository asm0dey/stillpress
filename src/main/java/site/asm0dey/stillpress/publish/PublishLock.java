package site.asm0dey.stillpress.publish;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import javax.sql.DataSource;

/**
 * Serializes publishes across the server, the CLI and replicas: the lock is a row lock on the {@code publish_lock}
 * setting, held on a connection of its own for as long as the work runs.
 */
@ApplicationScoped
public class PublishLock {
    @Inject
    DataSource dataSource;

    /**
     * Runs {@code work} while holding the lock, waiting for any other holder. The connection is opened outside the
     * caller's JTA transaction, so the caller's commit or rollback cannot release it early.
     */
    public <T> T holding(Supplier<T> work) {
        return QuarkusTransaction.suspendingExisting().call(() -> {
            try (var c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (var st = c.createStatement()) {
                    st.executeQuery("SELECT value FROM setting WHERE key = 'publish_lock' FOR UPDATE").close();
                    return work.get();
                } finally {
                    c.rollback();
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot take the publish lock", e);
            }
        });
    }
}
