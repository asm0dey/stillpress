package site.asm0dey.stillpress.store;

import module java.base;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.SQLStateClass;

/**
 * Retry loop for inserts that take {@code max(source_id) + 1}: a concurrent insert may win the id.
 */
public final class PublicIds {
    private static final int ATTEMPTS = 30;

    private PublicIds() {
    }

    /**
     * Runs {@code insert} until it returns a row. An empty result (PostgreSQL's native ON CONFLICT DO NOTHING) and a
     * {@code source_id} unique violation (H2 emulates the clause with MERGE and throws; its failed statement leaves the
     * transaction usable) both mean another writer took the id. Any other failure is rethrown.
     */
    public static <T> T insertUntilFree(Supplier<Optional<T>> insert) {
        for (var i = 0; i < ATTEMPTS; i++) {
            try {
                var row = insert.get();
                if (row.isPresent()) {
                    return row.get();
                }
            } catch (DataAccessException e) {
                if (e.sqlStateClass() != SQLStateClass.C23_INTEGRITY_CONSTRAINT_VIOLATION
                        || !e.getMessage().toLowerCase(Locale.ROOT).contains("source_id")) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("No free public id after " + ATTEMPTS + " attempts");
    }
}
