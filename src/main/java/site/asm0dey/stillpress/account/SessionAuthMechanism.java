package site.asm0dey.stillpress.account;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.store.SessionRepository;

/**
 * Authenticates requests by the {@code sp_session} cookie. No cookie, or a stale one, means anonymous: the public site
 * must keep working for a browser holding an expired session.
 */
@ApplicationScoped
public class SessionAuthMechanism implements HttpAuthenticationMechanism {
    public static final String COOKIE = "sp_session";

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        var cookie = context.request().getCookie(COOKIE);
        if (cookie == null || cookie.getValue().isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        var request =
                HttpSecurityUtils.setRoutingContextAttribute(
                        new SessionAuthenticationRequest(cookie.getValue()),
                        context
        );
        return identityProviderManager
            .authenticate(request)
            .onFailure(AuthenticationFailedException.class)
            .recoverWithNull();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, null, null));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(SessionAuthenticationRequest.class);
    }

    public static Cookie cookie(String raw, boolean secure) {
        return base(raw, secure).setMaxAge(SessionRepository.LIFETIME.toSeconds());
    }

    public static Cookie clearedCookie(boolean secure) {
        return base("", secure).setMaxAge(0);
    }

    private static Cookie base(String value, boolean secure) {
        return Cookie
            .cookie(COOKIE, value)
            .setPath("/")
            .setHttpOnly(true)
            .setSameSite(CookieSameSite.LAX)
            .setSecure(secure);
    }
}
