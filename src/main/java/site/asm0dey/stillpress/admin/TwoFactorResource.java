package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.account.Totp;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.db.tables.records.PasskeyRecord;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * The signed-in user's own second factors: the authenticator app here, passkeys next to it.
 */
@Path("/admin/api/2fa")
@Authenticated
public class TwoFactorResource {
    @Inject
    SecurityIdentity identity;
    @Inject
    Totp totp;
    @Inject
    TwoFactorRepository factors;

    public record PasskeyView(String id, String name, String createdAt, String lastUsedAt) {}

    public record Factors(boolean totp, List<PasskeyView> passkeys, boolean required) {}

    public record Code(String code) {}

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        return Response.status(e.status).entity(e.body()).build();
    }

    @GET
    public Factors list() {
        var id = userId();
        var confirmed = factors
            .totp(id)
            .filter(t -> t.getConfirmed())
            .isPresent();
        return new Factors(confirmed, factors.passkeys(id).stream().map(TwoFactorResource::view).toList(), false);
    }

    @POST
    @Path("/totp")
    public Totp.Enrollment enroll() {
        var id = userId();
        if (factors
            .totp(id)
            .filter(t -> t.getConfirmed())
            .isPresent()) {
            throw new ItemService.Refused(409, "totp_exists", null);
        }
        return totp.start(id, identity.getPrincipal().getName());
    }

    @POST
    @Path("/totp/confirm")
    public Response confirm(Code body) {
        if (body == null || !totp.confirm(userId(), body.code())) {
            throw new ItemService.Refused(400, "invalid_code", "code");
        }
        return Response.noContent().build();
    }

    @DELETE
    @Path("/totp")
    public Response remove() {
        if (factors.deleteTotp(userId()) == 0) {
            throw new ItemService.Refused(404, "not_found", null);
        }
        return Response.noContent().build();
    }

    private long userId() {
        return identity.getAttribute(SessionIdentityProvider.USER_ID);
    }

    private static PasskeyView view(PasskeyRecord p) {
        return new PasskeyView(
                p.getCredentialId(),
                p.getName(),
                p.getCreatedAt().toInstant(ZoneOffset.UTC).toString(),
                p.getLastUsedAt() == null ? null : p.getLastUsedAt().toInstant(ZoneOffset.UTC).toString()
        );
    }
}
