package site.asm0dey.stillpress.store;

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
    @Inject
    AuthorRepository users;

    /**
     * @return the raw cookie value
     */
    public String create(long userId) {
        var raw = randomToken(32);
        var now = now();
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
        var session = db.selectFrom(SESSION).where(SESSION.ID.eq(hash(rawToken))).fetchOptional();
        if (session.isEmpty()) {
            return Optional.empty();
        }
        var now = now();
        if (!session.get().getExpiresAt().isAfter(now)) {
            db.deleteFrom(SESSION).where(SESSION.EXPIRES_AT.le(now)).execute();
            return Optional.empty();
        }
        return users.findById(session.get().getUserId());
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
