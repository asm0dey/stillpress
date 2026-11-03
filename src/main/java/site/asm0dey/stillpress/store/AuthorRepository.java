package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;

@ApplicationScoped
public class AuthorRepository {
    @Inject
    DSLContext db;

    public Optional<AuthorRecord> findById(long id) {
        return db.selectFrom(AUTHOR).where(AUTHOR.ID.eq(id)).fetchOptional();
    }
}
