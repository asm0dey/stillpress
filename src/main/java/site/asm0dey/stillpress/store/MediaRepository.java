package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.MEDIA;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.db.tables.pojos.Media;

@ApplicationScoped
public class MediaRepository {
    private final DSLContext db;

    MediaRepository(DSLContext db) {
        this.db = db;
    }

    public Optional<Media> findBySource(String sourceUrl) {
        return db.selectFrom(MEDIA).where(MEDIA.SOURCE_URL.eq(sourceUrl)).fetchOptionalInto(Media.class);
    }

    public Optional<Media> findById(long id) {
        return db.selectFrom(MEDIA).where(MEDIA.ID.eq(id)).fetchOptionalInto(Media.class);
    }

    public record Page(List<Media> items, int total) {}

    public Media insert(
            String url,
            String mime,
            Integer width,
            Integer height,
            long size,
            long uploadedBy,
            LocalDateTime now
    ) {
        return db
            .insertInto(MEDIA)
            .set(MEDIA.SOURCE_URL, url)
            .set(MEDIA.PATH, url)
            .set(MEDIA.MIME_TYPE, mime)
            .set(MEDIA.WIDTH, width)
            .set(MEDIA.HEIGHT, height)
            .set(MEDIA.SIZE_BYTES, size)
            .set(MEDIA.UPLOADED_BY, uploadedBy)
            .set(MEDIA.CREATED_AT, now)
            .returning()
            .fetchSingleInto(Media.class);
    }

    /**
     * Newest first. {@code type} is {@code image}, {@code audio}, {@code video} or {@code file} (everything else);
     * {@code search} matches the title and the file path, case-insensitively. {@code page} counts from 1.
     */
    public Page list(String type, String search, int page, int perPage) {
        var mime = DSL.coalesce(MEDIA.MIME_TYPE, "");
        Condition where = switch (Objects.requireNonNullElse(type, "")) {
            case "image", "audio", "video" -> mime.startsWith(type + "/");
            case "file" -> mime.notLike("image/%").and(mime.notLike("audio/%")).and(mime.notLike("video/%"));
            default -> DSL.trueCondition();
        };
        if (search != null && !search.isBlank()) {
            where = where.and(DSL.or(
                    MEDIA.PATH.containsIgnoreCase(search.strip()),
                    MEDIA.TITLE.containsIgnoreCase(search.strip())
            ));
        }
        int total = db.fetchCount(MEDIA, where);
        var items = db
            .selectFrom(MEDIA)
            .where(where)
            .orderBy(MEDIA.ID.desc())
            .limit(perPage)
            .offset((Math.max(page, 1) - 1) * perPage)
            .fetchInto(Media.class);
        return new Page(items, total);
    }

    public void updateMeta(long id, String title, String altText) {
        db.update(MEDIA).set(MEDIA.TITLE, title).set(MEDIA.ALT_TEXT, altText).where(MEDIA.ID.eq(id)).execute();
    }
}
