package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.SETTING;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.SQLStateClass;

@ApplicationScoped
public class SettingRepository {
    @Inject
    DSLContext db;

    public Optional<String> get(String key) {
        return db.select(SETTING.VALUE).from(SETTING).where(SETTING.KEY.eq(key)).fetchOptional(SETTING.VALUE);
    }

    public void put(String key, String value) {
        if (db.update(SETTING).set(SETTING.VALUE, value).where(SETTING.KEY.eq(key)).execute() == 0) {
            db.insertInto(SETTING, SETTING.KEY, SETTING.VALUE).values(key, value).execute();
        }
    }

    /**
     * Sets {@code key} to {@code value} only if it currently holds {@code expected} ({@code null}: absent), in one
     * statement; false when another writer got there first.
     */
    public boolean compareAndSet(String key, String expected, String value) {
        if (expected == null) {
            try {
                return db
                    .insertInto(SETTING, SETTING.KEY, SETTING.VALUE)
                    .values(key, value)
                    .onConflictDoNothing()
                    .execute() == 1;
            } catch (DataAccessException e) {
                // H2 emulates the conflict clause with MERGE, which throws when a concurrent insert wins. PostgreSQL
                // renders a native ON CONFLICT DO NOTHING and never reaches this catch (it would abort the transaction)
                if (e.sqlStateClass() == SQLStateClass.C23_INTEGRITY_CONSTRAINT_VIOLATION) {
                    return false;
                }
                throw e;
            }
        }
        return db
            .update(SETTING)
            .set(SETTING.VALUE, value)
            .where(SETTING.KEY.eq(key), SETTING.VALUE.eq(expected))
            .execute() == 1;
    }

    public void delete(String key) {
        db.deleteFrom(SETTING).where(SETTING.KEY.eq(key)).execute();
    }
}
