package site.asm0dey.stillpress.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.account.Captcha;
import site.asm0dey.stillpress.content.ItemService;

/**
 * The CAPTCHA provider and its Turnstile keys. The secret is kept encrypted and never sent back.
 */
@Path("/admin/api/captcha")
@RolesAllowed("administrator")
public class CaptchaResource {
    private final Captcha captcha;

    CaptchaResource(Captcha captcha) {
        this.captcha = captcha;
    }

    public record Settings(String provider, String turnstileSiteKey, boolean secretSet, boolean locked) {}

    /**
     * {@code turnstileSecret}: {@code null} keeps the stored one. {@code turnstileToken}: solved with the new site key.
     */
    public record Update(String provider, String turnstileSiteKey, String turnstileSecret, String turnstileToken) {}

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        return Response.status(e.status).entity(e.body()).build();
    }

    @GET
    public Settings get() {
        return new Settings(
                captcha.provider(),
                captcha.turnstileSiteKey().orElse(null),
                captcha.turnstileSecretSet(),
                captcha.locked()
        );
    }

    @PUT
    public Settings put(Update u) {
        if (u == null) {
            throw new ItemService.Refused(400, "invalid", "provider");
        }
        captcha.save(u.provider(), u.turnstileSiteKey(), u.turnstileSecret(), u.turnstileToken());
        return get();
    }
}
