package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.REDIRECT;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.db.tables.records.RedirectRecord;

@ApplicationScoped
public class RedirectRepository {
    @Inject
    DSLContext db;

    public Optional<RedirectRecord> find(String key) {
        return db.selectFrom(REDIRECT).where(REDIRECT.FROM_PATH.eq(key)).fetchOptional();
    }

    /**
     * {@link #put} for many at once, in three statements: redirects that led to a key now lead to its target, then
     * each key and each target stops redirecting, then the keys redirect to their targets.
     * shortcut: one statement holds every path as a bind value, so a site with tens of thousands of terms would hit
     * PostgreSQL's parameter limit; chunk then.
     */
    public void putAll(Map<String, String> moves) {
        if (moves.isEmpty()) {
            return;
        }
        db
            .update(REDIRECT)
            .set(REDIRECT.TO_PATH, DSL.case_(REDIRECT.TO_PATH).mapValues(moves))
            .where(REDIRECT.TO_PATH.in(moves.keySet()))
            .execute();
        var both = new HashSet<>(moves.keySet());
        both.addAll(moves.values());
        db.deleteFrom(REDIRECT).where(REDIRECT.FROM_PATH.in(both)).execute();
        var insert = db.insertInto(REDIRECT, REDIRECT.FROM_PATH, REDIRECT.TO_PATH, REDIRECT.STATUS_CODE);
        moves.forEach((from, to) -> insert.values(from, to, 301));
        insert.execute();
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
