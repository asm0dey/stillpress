package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jooq.exception.IntegrityConstraintViolationException;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.account.Totp;
import site.asm0dey.stillpress.account.TwoFactorPolicy;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.db.tables.records.PasskeyRecord;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * The signed-in user's own second factors: the authenticator app here, passkeys next to it.
 */
@Path("/admin/api/2fa")
@Authenticated
public class TwoFactorResource {
    private static final Logger LOG = Logger.getLogger(TwoFactorResource.class);
    @Inject
    SecurityIdentity identity;
    @Inject
    Totp totp;
    @Inject
    TwoFactorRepository factors;
    @Inject
    WebAuthnSecurity webAuthn;
    @Inject
    TwoFactorPolicy policy;

    public record PasskeyView(String id, String name, String createdAt, String lastUsedAt) {}

    public record Factors(boolean totp, List<PasskeyView> passkeys, boolean required) {}

    public record Code(String code) {}

    public record NewPasskey(String name, JsonObject credential) {}

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
        return new Factors(
                confirmed,
                factors.passkeys(id).stream().map(TwoFactorResource::view).toList(),
                policy.required()
        );
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
    @Transactional
    public Response remove() {
        // an unconfirmed seed is a setup being cancelled, never a factor, so it is never the last one
        var confirmed = factors
            .totp(userId())
            .filter(t -> t.getConfirmed())
            .isPresent();
        if (factors.deleteTotp(userId()) == 0) {
            throw new ItemService.Refused(404, "not_found", null);
        }
        if (confirmed) {
            keepOneFactor();
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/passkeys")
    public Response addPasskey(NewPasskey body, RoutingContext ctx) {
        var name = body == null || body.name() == null ? "" : body.name().strip();
        // UTF-16 units, as H2 counts VARCHAR(100); a code-point limit would let 100 emoji through to a 500
        if (name.isEmpty() || name.length() > 100) {
            throw new ItemService.Refused(400, "invalid", "name");
        }
        WebAuthnCredentialRecord passkey;
        try {
            // the extension does not bind the challenge to its username; ownership comes from the session alone
            passkey = webAuthn
                .register(identity.getPrincipal().getName(), body.credential(), ctx)
                .await()
                .atMost(Duration.ofSeconds(10));
        } catch (RuntimeException e) {
            LOG.debugf(e, "passkey registration refused");
            throw new ItemService.Refused(400, "invalid_passkey", null);
        }
        var data = passkey.getRequiredPersistedData();
        try {
            factors.addPasskey(userId(), name, data);
        } catch (IntegrityConstraintViolationException e) {
            // a replayed registration: that credential is already stored
            throw new ItemService.Refused(400, "invalid_passkey", null);
        }
        return Response.status(201).entity(view(factors.passkey(data.credentialId()).orElseThrow())).build();
    }

    @DELETE
    @Path("/passkeys/{id}")
    @Transactional
    public Response removePasskey(@PathParam("id") String id) {
        if (factors.deletePasskey(userId(), id) == 0) {
            throw new ItemService.Refused(404, "not_found", null);
        }
        keepOneFactor();
        return Response.noContent().build();
    }

    /**
     * Run after deleting a factor: when it was the last one while 2FA is mandatory, the refusal rolls it back.
     */
    private void keepOneFactor() {
        // shortcut: two concurrent deletes of a user's last two factors can leave none; the enrollment fence catches them on the next request — add a row lock if that ever matters
        if (policy.required() && !factors.hasFactor(userId())) {
            throw new ItemService.Refused(409, "last_factor", null);
        }
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
