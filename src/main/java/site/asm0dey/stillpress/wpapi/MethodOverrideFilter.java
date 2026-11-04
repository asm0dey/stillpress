package site.asm0dey.stillpress.wpapi;

import jakarta.ws.rs.container.ContainerRequestContext;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * apiFetch sends {@code PUT}, {@code PATCH} and {@code DELETE} as {@code POST} with {@code X-HTTP-Method-Override};
 * honoured for {@code /wp-json/} only. CSRF still applies: the new method is unsafe too.
 */
public class MethodOverrideFilter {
    private static final Set<String> OVERRIDES = Set.of("PUT", "PATCH", "DELETE");

    @ServerRequestFilter(preMatching = true)
    public void override(ContainerRequestContext ctx) {
        var method = ctx.getHeaderString("X-HTTP-Method-Override");
        if ("POST".equals(ctx.getMethod())
                && method != null
                && OVERRIDES.contains(method.toUpperCase(Locale.ROOT))
                && ctx.getUriInfo().getPath().startsWith("/wp-json/")) {
            ctx.setMethod(method.toUpperCase(Locale.ROOT));
        }
    }
}
