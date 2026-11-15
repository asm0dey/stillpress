package site.asm0dey.stillpress.admin;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import org.altcha.altcha.v1.Altcha;
import site.asm0dey.stillpress.account.Captcha;

/**
 * Public: mints the proof-of-work challenge the ALTCHA widget solves; issuing one is cheap and safe to expose.
 */
@Path("/admin/api/altcha")
public class AltchaResource {
    private final Captcha captcha;

    AltchaResource(Captcha captcha) {
        this.captcha = captcha;
    }

    @GET
    @Path("challenge")
    public Altcha.Challenge challenge() {
        if (!"altcha".equals(captcha.provider())) {
            throw new NotFoundException();
        }
        return captcha.challenge();
    }
}
