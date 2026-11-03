package site.asm0dey.stillpress.account;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Test-only endpoint guarded by a role, to exercise the session mechanism.
 */
@Path("/admin/api/test/whoami")
public class WhoamiResource {
    @GET
    @RolesAllowed("administrator")
    public String whoami(@Context SecurityContext ctx) {
        return ctx.getUserPrincipal().getName();
    }
}
