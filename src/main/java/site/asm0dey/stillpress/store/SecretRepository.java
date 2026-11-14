package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.SECRET;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.SecretRecord;

/**
 * Raw access to the {@code secret} table; {@code Secrets} encrypts and decrypts.
 */
@ApplicationScoped
public class SecretRepository {
    @Inject
    DSLContext db;

    public Optional<String> ciphertext(String name) {
        return db
            .select(SECRET.CIPHERTEXT)
            .from(SECRET)
            .where(SECRET.NAME.eq(name))
            .fetchOptional(SECRET.CIPHERTEXT);
    }

    /**
     * Any one stored secret: enough to tell which keyset the rows belong to.
     */
    public Optional<SecretRecord> first() {
        return db.selectFrom(SECRET).limit(1).fetchOptional();
    }

    public void put(String name, String ciphertext) {
        if (db.update(SECRET).set(SECRET.CIPHERTEXT, ciphertext).where(SECRET.NAME.eq(name)).execute() == 0) {
            db.insertInto(SECRET, SECRET.NAME, SECRET.CIPHERTEXT).values(name, ciphertext).execute();
        }
    }

    public void delete(String name) {
        db.deleteFrom(SECRET).where(SECRET.NAME.eq(name)).execute();
    }

    public int deleteAll() {
        return db.deleteFrom(SECRET).execute();
    }

    public boolean any() {
        return db.fetchExists(SECRET);
    }
}
