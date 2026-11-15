package site.asm0dey.stillpress.account;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;

/**
 * Only an {@code sp_session} identity may be authenticated. The WebAuthn extension seals its challenge and credential
 * cookies with one key and no name binding, so a challenge cookie replayed as {@code quarkus-credential} would
 * otherwise sign in "the challenge string"; this also covers any mechanism added later.
 */
@ApplicationScoped
public class SessionOnlyAugmentor implements SecurityIdentityAugmentor {
    private final SessionAuthMechanism sessionMechanism;

    SessionOnlyAugmentor(SessionAuthMechanism sessionMechanism) {
        this.sessionMechanism = sessionMechanism;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        return augment(identity, context, Map.of());
    }

    @Override
    public Uni<SecurityIdentity> augment(
            SecurityIdentity identity,
            AuthenticationRequestContext context,
            Map<String, Object> attributes
    ) {
        if (identity.isAnonymous() || identity.getAttribute(SessionIdentityProvider.USER_ID) != null) {
            return Uni.createFrom().item(identity);
        }
        var routing = HttpSecurityUtils.getRoutingContextAttribute(attributes);
        if (routing != null) {
            // the failing mechanism registered itself here, and HttpAuthenticator asks it for the challenge: WebAuthn
            // would answer with a redirect to its login page, ours with the plain 401
            routing.put(HttpAuthenticationMechanism.class.getName(), sessionMechanism);
        }
        return Uni.createFrom().failure(new AuthenticationFailedException("not an sp_session identity"));
    }
}
