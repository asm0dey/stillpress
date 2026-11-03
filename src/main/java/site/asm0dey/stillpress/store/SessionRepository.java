package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.SESSION;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;

/**
 * Server-side sessions. The cookie carries a random token; the database keeps only its SHA-256.
 */
@ApplicationScoped
public class SessionRepository {
    public static final Duration LIFETIME = Duration.ofDays(14);
    private static final SecureRandom RANDOM = new SecureRandom();
    @Inject
    DSLContext db;

    /**
     * @return the raw cookie value
     */
    public String create(long userId) {
        var raw = randomToken(32);
        var now = now();
        // housekeeping on login rather than on every authenticated request
        db.deleteFrom(SESSION).where(SESSION.EXPIRES_AT.le(now)).execute();
        db
            .insertInto(SESSION, SESSION.ID, SESSION.USER_ID, SESSION.CREATED_AT, SESSION.EXPIRES_AT)
            .values(hash(raw), userId, now, now.plus(LIFETIME))
            .execute();
        return raw;
    }

    public Optional<AuthorRecord> find(String rawToken) {
        if (rawToken == null) {
            return Optional.empty();
        }
        return db
            .select(AUTHOR.fields())
            .from(SESSION.join(AUTHOR).on(AUTHOR.ID.eq(SESSION.USER_ID)))
            .where(SESSION.ID.eq(hash(rawToken)), SESSION.EXPIRES_AT.gt(now()))
            .fetchOptionalInto(AUTHOR);
    }

    public void delete(String rawToken) {
        if (rawToken != null) {
            db.deleteFrom(SESSION).where(SESSION.ID.eq(hash(rawToken))).execute();
        }
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    static String randomToken(int bytes) {
        var b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String raw) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
