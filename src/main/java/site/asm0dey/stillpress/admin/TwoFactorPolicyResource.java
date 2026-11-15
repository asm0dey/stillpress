package site.asm0dey.stillpress.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.account.TwoFactorPolicy;
import site.asm0dey.stillpress.content.ItemService;

/**
 * Whether every user must have a second factor. A security setting, not site content: it never republishes.
 */
@Path("/admin/api/security/two-factor")
@RolesAllowed("administrator")
public class TwoFactorPolicyResource {
    @Inject
    TwoFactorPolicy policy;
    @Inject
    SecurityIdentity identity;

    public record Policy(Boolean required) {}

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        return Response.status(e.status).entity(e.body()).build();
    }

    @GET
    public Policy get() {
        return new Policy(policy.required());
    }

    @PUT
    public Policy put(Policy p) {
        if (p == null || p.required() == null) {
            throw new ItemService.Refused(400, "invalid", "required");
        }
        policy.set(p.required(), identity.getAttribute(SessionIdentityProvider.USER_ID));
        return get();
    }
}
