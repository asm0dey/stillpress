package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;

/**
 * Single-use, purpose-bound user tokens (e.g. {@code set_password}); only their SHA-256 is stored.
 */
@ApplicationScoped
public class TokenRepository {
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
        var id = SessionRepository.hash(rawToken);
        var token =
                db.selectFrom(USER_TOKEN).where(USER_TOKEN.ID.eq(id), USER_TOKEN.PURPOSE.eq(purpose)).fetchOptional();
        // only the request whose delete hits the row may use it, so concurrent consumers cannot both win
        if (token.isEmpty() || db.deleteFrom(USER_TOKEN).where(USER_TOKEN.ID.eq(id)).execute() == 0) {
            return Optional.empty();
        }
        return token.get().getExpiresAt().isAfter(SessionRepository.now())
                ? Optional.of(token.get().getUserId())
                : Optional.empty();
    }
}
