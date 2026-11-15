package site.asm0dey.stillpress.account;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * Turns a session token into an identity: principal = login, role = the user's role, attribute {@code userId}, and
 * {@code enrollRequired} when mandatory 2FA is on and the user has no factor yet.
 */
@ApplicationScoped
public class SessionIdentityProvider implements IdentityProvider<SessionAuthenticationRequest> {
    public static final String USER_ID = "userId";
    public static final String ENROLL_REQUIRED = "enrollRequired";
    private final SessionRepository sessions;
    private final TwoFactorPolicy policy;
    private final TwoFactorRepository factors;

    SessionIdentityProvider(SessionRepository sessions, TwoFactorPolicy policy, TwoFactorRepository factors) {
        this.sessions = sessions;
        this.policy = policy;
        this.factors = factors;
    }

    @Override
    public Class<SessionAuthenticationRequest> getRequestType() {
        return SessionAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            SessionAuthenticationRequest request,
            AuthenticationRequestContext context
    ) {
        return context.runBlocking(() -> sessions
            .find(request.token())
            .map(u -> (SecurityIdentity) QuarkusSecurityIdentity
                .builder()
                .setPrincipal(new QuarkusPrincipal(u.getLogin()))
                .addRole(u.getRole())
                .addAttribute(USER_ID, u.getId())
                .addAttribute(ENROLL_REQUIRED, policy.required() && !factors.hasFactor(u.getId()))
                .build())
            .orElseThrow(AuthenticationFailedException::new));
    }
}
