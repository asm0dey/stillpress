package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import site.asm0dey.stillpress.db.tables.records.UserTokenRecord;

/**
 * Single-use, purpose-bound user tokens (e.g. {@code set_password}); only their SHA-256 is stored.
 */
@ApplicationScoped
public class TokenRepository {
    public static final String INVITE = "invite";
    public static final String SET_PASSWORD = "set_password";
    public static final Duration SET_PASSWORD_TTL = Duration.ofHours(24);
    /**
     * A correct password of a user with a second factor: the half-way sign-in the second step finishes.
     */
    public static final String SECOND_FACTOR = "second_factor";
    public static final Duration SECOND_FACTOR_TTL = Duration.ofMinutes(5);
    private final DSLContext db;

    TokenRepository(DSLContext db) {
        this.db = db;
    }

    /**
     * The user's one live token of this purpose, replacing any earlier one.
     */
    @Transactional
    public String create(long userId, String purpose, Duration ttl) {
        return upsert(userId, purpose, ttl, null).orElseThrow();
    }

    /**
     * Like {@link #create}, but only when the user has no token of this purpose created at or after {@code since}; the
     * check and the write are one statement, so concurrent callers cannot both get a token.
     *
     * @return the raw token, or empty when a newer one exists
     */
    @Transactional
    public Optional<String> createUnlessIssuedSince(long userId, String purpose, Duration ttl, Instant since) {
        return upsert(userId, purpose, ttl, LocalDateTime.ofInstant(since, ZoneOffset.UTC));
    }

    private Optional<String> upsert(long userId, String purpose, Duration ttl, LocalDateTime since) {
        var raw = SessionRepository.randomToken(32);
        var now = SessionRepository.now();
        var id = SessionRepository.hash(raw);
        var expires = now.plus(ttl);
        var update = db
            .insertInto(
                    USER_TOKEN,
                    USER_TOKEN.ID,
                    USER_TOKEN.USER_ID,
                    USER_TOKEN.PURPOSE,
                    USER_TOKEN.EXPIRES_AT,
                    USER_TOKEN.CREATED_AT
            )
            .values(id, userId, purpose, expires, now)
            .onConflict(USER_TOKEN.USER_ID, USER_TOKEN.PURPOSE)
            .doUpdate()
            .set(USER_TOKEN.ID, id)
            .set(USER_TOKEN.EXPIRES_AT, expires)
            .set(USER_TOKEN.CREATED_AT, now);
        var statement = since == null ? update : update.where(USER_TOKEN.CREATED_AT.lt(since));
        int changed;
        try {
            changed = statement.execute();
        } catch (IntegrityConstraintViolationException e) {
            // H2 emulates the upsert with MERGE, which loses a race to a concurrent insert with this error; the row is
            // there now, so the second try takes the update branch (PostgreSQL never gets here)
            changed = statement.execute();
        }
        return changed == 0 ? Optional.empty() : Optional.of(raw);
    }

    /**
     * Deletes the token and returns its user; empty if the token is unknown, expired or for another purpose.
     */
    public Optional<Long> consumeAny(String rawToken, Set<String> purposes) {
        if (rawToken == null) {
            return Optional.empty();
        }
        // the delete is the claim: concurrent consumers cannot both get the row back
        return db
            .deleteFrom(USER_TOKEN)
            .where(USER_TOKEN.ID.eq(SessionRepository.hash(rawToken)), USER_TOKEN.PURPOSE.in(purposes))
            .returning(USER_TOKEN.USER_ID, USER_TOKEN.EXPIRES_AT)
            .fetchOptional()
            // expiry is checked here, not in where: the delete also removes an expired token, else it would stay in user_token forever
            .filter(t -> t.getExpiresAt().isAfter(SessionRepository.now()))
            .map(UserTokenRecord::getUserId);
    }

    /**
     * The token's user while it is unexpired; the token stays.
     */
    public Optional<Long> peek(String rawToken, String purpose) {
        if (rawToken == null) {
            return Optional.empty();
        }
        return db
            .select(USER_TOKEN.USER_ID)
            .from(USER_TOKEN)
            .where(
                    USER_TOKEN.ID.eq(SessionRepository.hash(rawToken)),
                    USER_TOKEN.PURPOSE.eq(purpose),
                    USER_TOKEN.EXPIRES_AT.gt(SessionRepository.now())
            )
            .fetchOptional(USER_TOKEN.USER_ID);
    }

    /**
     * Revokes every token of these purposes for the user.
     */
    public void deleteAll(long userId, Set<String> purposes) {
        db.deleteFrom(USER_TOKEN).where(USER_TOKEN.USER_ID.eq(userId), USER_TOKEN.PURPOSE.in(purposes)).execute();
    }

    /**
     * {@link #consumeAny} for one purpose.
     */
    public Optional<Long> consume(String rawToken, String purpose) {
        return consumeAny(rawToken, Set.of(purpose));
    }
}
