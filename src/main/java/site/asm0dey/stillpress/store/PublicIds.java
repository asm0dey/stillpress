package site.asm0dey.stillpress.store;

import java.sql.SQLException;
import module java.base;
import org.jooq.exception.DataAccessException;

/**
 * Retry loop for inserts that take {@code max(source_id) + 1}: a concurrent insert may win the id.
 */
public final class PublicIds {
    private static final int ATTEMPTS = 30;

    private PublicIds() {
    }

    // The driver's own error: jOOQ's wrapper message embeds the whole statement, which always names source_id. H2 words
    // it "Unique index or primary key violation: ... ON PUBLIC.ITEM(SOURCE_ID ...)" and appends "; SQL statement:";
    // PostgreSQL names the constraint, item_source_id_key / reusable_block_source_id_key.
    private static boolean isSourceIdClash(DataAccessException e) {
        return e.getCause() instanceof SQLException driver
                && "23505".equals(driver.getSQLState())
                && driver.getMessage().split("; SQL statement:")[0].toLowerCase(Locale.ROOT).contains("source_id");
    }

    /**
     * Runs {@code insert} until it returns a row. An empty result (PostgreSQL's native ON CONFLICT DO NOTHING) and a
     * {@code source_id} unique violation (SQLState 23505) (H2 emulates the clause with MERGE and throws; its failed statement leaves the
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
                if (!isSourceIdClash(e)) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("No free public id after " + ATTEMPTS + " attempts");
    }
}
