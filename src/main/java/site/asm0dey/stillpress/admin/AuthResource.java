package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;
import site.asm0dey.stillpress.account.LoginLimiter;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.SessionAuthMechanism;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.TokenRepository;

/**
 * Password login, logout, the current session, and setting a password from a single-use token.
 */
@Path("/admin/api")
public class AuthResource {
    // an unknown login is verified against this, so it costs the same as a wrong password
    private static final String DUMMY_HASH = Passwords.hash("no such user, same Argon2id cost");
    private static final int MAX_LOGIN = 100;
    private static final Map<String, String> INVALID_CREDENTIALS = Map.of("error", "invalid_credentials");
    @Inject
    LoginLimiter limiter;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    TokenRepository tokens;
    @Inject
    SecurityIdentity identity;
    @Context
    HttpServerRequest request;

    public record Credentials(String login, String password) {}

    public record NewPassword(String token, String password) {}

    public record SessionUser(long id, String login, String displayName, String role) {}

    @POST
    @Path("login")
    public Response login(Credentials c) {
        if (c == null || c.login() == null || c.password() == null) {
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        // the proxy-forwarding layer has already replaced this with the client's address when a trusted proxy sent it
        var ip = request.remoteAddress().hostAddress();
        var wait = limiter.blockedIp(ip);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        // no real login or password is this long: refuse before it becomes a limiter key or Argon2 input, counting
        // it against the IP only so oversized spam still runs into the IP limit
        if (c.login().length() > MAX_LOGIN
                || c.password().codePointCount(0, c.password().length()) > Passwords.MAX_LENGTH) {
            limiter.ipFailure(ip);
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        var user = users.findByLoginOrEmail(c.login());
        // the account bucket follows the user, so login and email share it; unknown names get their own
        var account = user
            .map(u -> "user:" + u.getId())
            .orElse("login:" + c.login());
        wait = limiter.blockedAccount(account);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        var hash = user.map(AuthorRecord::getPasswordHash).orElse(null);
        // a user without a password (imported) also pays the full hash cost, and never succeeds
        var ok = Passwords.verify(c.password(), hash == null ? DUMMY_HASH : hash) && hash != null;
        if (!ok) {
            limiter.failure(account, ip);
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        limiter.success(account);
        var raw = sessions.create(user.get().getId());
        return Response
            .noContent()
            .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.cookie(raw, secure()).encode())
            .build();
    }

    @POST
    @Path("logout")
    public Response logout(@CookieParam(SessionAuthMechanism.COOKIE) String raw) {
        sessions.delete(raw);
        return Response
            .noContent()
            .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.clearedCookie(secure()).encode())
            .build();
    }

    @GET
    @Path("session")
    @Authenticated
    public SessionUser session() {
        long id = identity.getAttribute(SessionIdentityProvider.USER_ID);
        var u = users.findById(id).orElseThrow();
        return new SessionUser(u.getId(), u.getLogin(), u.getDisplayName(), u.getRole());
    }

    @POST
    @Path("set-password")
    @Transactional
    public Response setPassword(NewPassword p) {
        var password = p == null ? null : p.password();
        try {
            Passwords.validate(password);
        } catch (IllegalArgumentException e) {
            // checked before the token is spent, so the user can retry with a longer password
            return Response.status(400).entity(Map.of("error", "weak_password")).build();
        }
        var hash = Passwords.hash(password);
        // one transaction: the token is spent only if the password is set and every old session is revoked
        var userId = tokens.consume(p.token(), TokenRepository.SET_PASSWORD);
        if (userId.isEmpty()) {
            return Response.status(410).entity(Map.of("error", "invalid_token")).build();
        }
        users.setPassword(userId.get(), hash);
        sessions.deleteAllFor(userId.get());
        return Response.noContent().build();
    }

    private static Response rateLimited(Duration wait) {
        // whole seconds, rounded up: retrying at the stated time must not be refused again
        var seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return Response
            .status(429)
            .header(HttpHeaders.RETRY_AFTER, seconds)
            .entity(Map.of("error", "rate_limited"))
            .build();
    }

    private boolean secure() {
        return "https".equals(request.scheme());
    }
}
