package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.UserTokenRecord;

/**
 * Single-use, purpose-bound user tokens (e.g. {@code set_password}); only their SHA-256 is stored.
 */
@ApplicationScoped
public class TokenRepository {
    public static final String INVITE = "invite";
    public static final String SET_PASSWORD = "set_password";
    public static final Duration SET_PASSWORD_TTL = Duration.ofHours(24);
    @Inject
    DSLContext db;

    @Transactional
    public String create(long userId, String purpose, Duration ttl) {
        var raw = SessionRepository.randomToken(32);
        // a new link revokes the earlier ones
        db.deleteFrom(USER_TOKEN).where(USER_TOKEN.USER_ID.eq(userId), USER_TOKEN.PURPOSE.eq(purpose)).execute();
        var now = SessionRepository.now();
        db
            .insertInto(
                    USER_TOKEN,
                    USER_TOKEN.ID,
                    USER_TOKEN.USER_ID,
                    USER_TOKEN.PURPOSE,
                    USER_TOKEN.EXPIRES_AT,
                    USER_TOKEN.CREATED_AT
            )
            .values(SessionRepository.hash(raw), userId, purpose, now.plus(ttl), now)
            .execute();
        return raw;
    }

    /**
     * Whether an unspent token of this purpose was created for the user at or after {@code since}.
     */
    public boolean issuedSince(long userId, String purpose, Instant since) {
        return db.fetchExists(
                USER_TOKEN,
                USER_TOKEN.USER_ID.eq(userId),
                USER_TOKEN.PURPOSE.eq(purpose),
                USER_TOKEN.CREATED_AT.ge(LocalDateTime.ofInstant(since, ZoneOffset.UTC))
        );
    }

    /**
     * Like {@link #consume}, for a token of any of the purposes.
     */
    public Optional<Long> consumeAny(String rawToken, Set<String> purposes) {
        if (rawToken == null) {
            return Optional.empty();
        }
        return db
            .deleteFrom(USER_TOKEN)
            .where(USER_TOKEN.ID.eq(SessionRepository.hash(rawToken)), USER_TOKEN.PURPOSE.in(purposes))
            .returning(USER_TOKEN.USER_ID, USER_TOKEN.EXPIRES_AT)
            .fetchOptional()
            .filter(t -> t.getExpiresAt().isAfter(SessionRepository.now()))
            .map(UserTokenRecord::getUserId);
    }

    /**
     * Revokes every token of these purposes for the user.
     */
    public void deleteAll(long userId, Set<String> purposes) {
        db.deleteFrom(USER_TOKEN).where(USER_TOKEN.USER_ID.eq(userId), USER_TOKEN.PURPOSE.in(purposes)).execute();
    }

    /**
     * Deletes the token and returns its user; empty if the token is unknown, expired or for another purpose.
     */
    public Optional<Long> consume(String rawToken, String purpose) {
        if (rawToken == null) {
            return Optional.empty();
        }
        // the delete is the claim: concurrent consumers cannot both get the row back
        return db
            .deleteFrom(USER_TOKEN)
            .where(USER_TOKEN.ID.eq(SessionRepository.hash(rawToken)), USER_TOKEN.PURPOSE.eq(purpose))
            .returning(USER_TOKEN.USER_ID, USER_TOKEN.EXPIRES_AT)
            .fetchOptional()
            // expiry is checked here, not in where: the delete also removes an expired token, else it would stay in user_token forever
            .filter(t -> t.getExpiresAt().isAfter(SessionRepository.now()))
            .map(UserTokenRecord::getUserId);
    }
}
