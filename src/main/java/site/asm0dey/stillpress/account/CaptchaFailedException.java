package site.asm0dey.stillpress.account;

import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * A missing or wrong CAPTCHA answer, or a filled honeypot; every endpoint answers it with 400 {@code captcha_failed}.
 */
public class CaptchaFailedException extends RuntimeException {
    public CaptchaFailedException(String message) {
        super(message);
    }

    public static class Mapper {
        @ServerExceptionMapper
        public Response captchaFailed(CaptchaFailedException e) {
            return Response.status(400).entity(Map.of("error", "captcha_failed")).build();
        }
    }
}
