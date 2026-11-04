package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.SETTING;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;

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
            return db
                .insertInto(SETTING, SETTING.KEY, SETTING.VALUE)
                .values(key, value)
                .onConflictDoNothing()
                .execute() == 1;
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
