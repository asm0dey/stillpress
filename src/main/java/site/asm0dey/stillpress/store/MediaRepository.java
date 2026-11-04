package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.MEDIA;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.MediaRecord;

@ApplicationScoped
public class MediaRepository {
    @Inject
    DSLContext db;

    public Optional<MediaRecord> findBySource(String sourceUrl) {
        return db.selectFrom(MEDIA).where(MEDIA.SOURCE_URL.eq(sourceUrl)).fetchOptional();
    }

    public Optional<MediaRecord> findById(long id) {
        return db.selectFrom(MEDIA).where(MEDIA.ID.eq(id)).fetchOptional();
    }

    public List<MediaRecord> findByIds(Collection<Long> ids) {
        return db.selectFrom(MEDIA).where(MEDIA.ID.in(ids)).orderBy(MEDIA.ID).fetch();
    }
}
