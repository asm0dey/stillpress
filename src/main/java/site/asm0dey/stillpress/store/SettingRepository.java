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
}
