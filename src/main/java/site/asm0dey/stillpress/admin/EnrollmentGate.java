package site.asm0dey.stillpress.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
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
            "GET /admin/api/login-options"
    );
    @Inject
    SecurityIdentity identity;

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
