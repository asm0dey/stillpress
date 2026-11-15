package site.asm0dey.stillpress.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import site.asm0dey.stillpress.account.SessionIdentityProvider;

/**
 * With mandatory 2FA on, a session without a factor reaches only what it needs to enroll one. Every REST endpoint is
 * fenced, the preview included; the admin UI, the static site and the WebAuthn challenge routes are not REST.
 */
public class EnrollmentGate {
    private static final Set<String> OPEN = Set.of(
            "GET /admin/api/session",
            "PUT /admin/api/session/locale",
            "POST /admin/api/logout",
            "GET /admin/api/login-options",
            "GET /admin/api/setup",
            // anonymous: they read no identity, so the cookie must not lock this browser out of them
            "POST /admin/api/login",
            "POST /admin/api/login/totp",
            "POST /admin/api/login/passkey-step",
            "POST /admin/api/login/passkey",
            "POST /admin/api/password-reset",
            "POST /admin/api/set-password",
            "GET /admin/api/altcha/challenge"
    );
    private final SecurityIdentity identity;

    EnrollmentGate(SecurityIdentity identity) {
        this.identity = identity;
    }

    @ServerRequestFilter
    public Optional<Response> fence(ContainerRequestContext ctx) {
        if (!Boolean.TRUE.equals(identity.getAttribute(SessionIdentityProvider.ENROLL_REQUIRED))) {
            return Optional.empty();
        }
        var path = ctx.getUriInfo().getPath();
        if (OPEN.contains(ctx.getMethod() + " " + path)
                || path.equals("/admin/api/2fa")
                || path.startsWith("/admin/api/2fa/")) {
            return Optional.empty();
        }
        return Optional.of(Response
            .status(403)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(Map.of("error", "enroll_2fa"))
            .build()
        );
    }
}
