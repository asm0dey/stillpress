package site.asm0dey.stillpress.admin;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
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
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * "Reset site": back to the state right after the wizard's first step, keeping the calling administrator.
 */
@Path("/admin/api/reset")
public class ResetResource {
    @Inject
    SiteReset reset;
    @Inject
    LoginLimiter limiter;
    @Inject
    AuthorRepository users;
    @Inject
    SettingRepository settings;
    @Inject
    SecurityIdentity identity;
    @Context
    HttpServerRequest request;

    public record Confirmation(String password, String confirm) {}

    @POST
    @RolesAllowed("administrator")
    public Response reset(Confirmation c, @CookieParam(SessionAuthMechanism.COOKIE) String session) {
        if (c == null || c.confirm() == null || !c.confirm().equals(settings.get("title").orElse(""))) {
            return Response.status(400).entity(Map.of("error", "invalid", "field", "confirm")).build();
        }
        long id = identity.getAttribute(SessionIdentityProvider.USER_ID);
        // the same buckets as login, so a stolen session cannot guess the password here faster than at the door
        var ip = request.remoteAddress().hostAddress();
        var account = "user:" + id;
        var wait = limiter
            .blockedIp(ip)
            .or(() -> limiter.blockedAccount(account));
        if (wait.isPresent()) {
            var seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            return Response
                .status(429)
                .header(HttpHeaders.RETRY_AFTER, seconds)
                .entity(Map.of("error", "rate_limited"))
                .build();
        }
        var password = c.password();
        var ok = password != null
                && password.codePointCount(0, password.length()) <= Passwords.MAX_LENGTH
                && Passwords.verify(password, users.findById(id).orElseThrow().getPasswordHash());
        if (!ok) {
            limiter.failure(account, ip);
            return Response.status(403).entity(Map.of("error", "invalid_credentials")).build();
        }
        limiter.success(account);
        // an import in progress surfaces as ImportRunningException, mapped to 409
        if (!reset.reset(id, session)) {
            return Response.status(500).entity(Map.of("error", "files")).build();
        }
        return Response.noContent().build();
    }
}
