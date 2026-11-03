package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.REDIRECT;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.RedirectRecord;

@ApplicationScoped
public class RedirectRepository {
    @Inject
    DSLContext db;

    public Optional<RedirectRecord> find(String key) {
        return db.selectFrom(REDIRECT).where(REDIRECT.FROM_PATH.eq(key)).fetchOptional();
    }
}
