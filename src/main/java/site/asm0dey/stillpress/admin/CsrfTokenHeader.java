package site.asm0dey.stillpress.admin;

import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Hands the admin UI the raw CSRF token in {@code X-CSRF-TOKEN}: with a signature key the csrf-token cookie holds only
 * the token's HMAC, and the UI must send the raw token back in the same header.
 */
public class CsrfTokenHeader {
    /**
     * Where quarkus-rest-csrf keeps the request's raw token (its Qute {@code csrf.token} provider reads the same key)
     */
    private static final String TOKEN_KEY = "csrf_token";

    @ServerResponseFilter
    public void expose(ContainerResponseContext response, RoutingContext routing) {
        String token = routing.get(TOKEN_KEY);
        if (token != null) {
            response.getHeaders().putSingle("X-CSRF-TOKEN", token);
        }
    }
}
