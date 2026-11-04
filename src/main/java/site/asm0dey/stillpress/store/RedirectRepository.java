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

    /**
     * A 301 from {@code from} to {@code to}. Redirects that led to {@code from} now lead to {@code to}, so no chain
     * forms, and one from {@code to} itself goes: that path is live again.
     */
    public void put(String from, String to) {
        db.update(REDIRECT).set(REDIRECT.TO_PATH, to).where(REDIRECT.TO_PATH.eq(from)).execute();
        db.deleteFrom(REDIRECT).where(REDIRECT.FROM_PATH.in(from, to)).execute();
        db.insertInto(REDIRECT, REDIRECT.FROM_PATH, REDIRECT.TO_PATH, REDIRECT.STATUS_CODE).values(from, to, 301).execute();
    }
}
