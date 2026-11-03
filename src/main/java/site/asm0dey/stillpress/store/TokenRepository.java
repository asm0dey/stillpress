package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.tables.records.UserTokenRecord;

/**
 * Single-use, purpose-bound user tokens (e.g. {@code set_password}); only their SHA-256 is stored.
 */
@ApplicationScoped
public class TokenRepository {
    public static final String SET_PASSWORD = "set_password";
    public static final Duration SET_PASSWORD_TTL = Duration.ofHours(24);
    @Inject
    DSLContext db;

    public String create(long userId, String purpose, Duration ttl) {
        var raw = SessionRepository.randomToken(32);
        db
            .insertInto(USER_TOKEN, USER_TOKEN.ID, USER_TOKEN.USER_ID, USER_TOKEN.PURPOSE, USER_TOKEN.EXPIRES_AT)
            .values(SessionRepository.hash(raw), userId, purpose, SessionRepository.now().plus(ttl))
            .execute();
        return raw;
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
