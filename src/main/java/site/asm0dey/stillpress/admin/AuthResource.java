package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import module java.base;
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
        var wait = limiter.blocked(c.login(), ip);
        if (wait.isPresent()) {
            // whole seconds, rounded up: retrying at the stated time must not be refused again
            var seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            return Response
                .status(429)
                .header(HttpHeaders.RETRY_AFTER, seconds)
                .entity(Map.of("error", "rate_limited"))
                .build();
        }
        var user = users.findByLoginOrEmail(c.login());
        var hash = user.map(u -> u.getPasswordHash()).orElse(null);
        // a user without a password (imported) also pays the full hash cost, and never succeeds
        var ok = Passwords.verify(c.password(), hash == null ? DUMMY_HASH : hash) && hash != null;
        if (!ok) {
            limiter.failure(c.login(), ip);
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        limiter.success(c.login());
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
    public Response setPassword(NewPassword p) {
        var password = p == null ? null : p.password();
        try {
            Passwords.validate(password);
        } catch (IllegalArgumentException e) {
            // checked before the token is spent, so the user can retry with a longer password
            return Response.status(400).entity(Map.of("error", "weak_password")).build();
        }
        var userId = tokens.consume(p.token(), TokenRepository.SET_PASSWORD);
        if (userId.isEmpty()) {
            return Response.status(410).entity(Map.of("error", "invalid_token")).build();
        }
        users.setPassword(userId.get(), Passwords.hash(password));
        return Response.noContent().build();
    }

    private boolean secure() {
        return "https".equals(request.scheme());
    }
}
