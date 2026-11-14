package site.asm0dey.stillpress.admin;

import io.vertx.core.http.HttpServerRequest;
import module java.base;

/**
 * The origin ({@code scheme://host[:port]}) a request arrived at, and the check for one an administrator typed.
 */
public final class Origins {
    private Origins() {
    }

    /**
     * Empty when the request carries no authority (an HTTP/1.0 request without Host).
     */
    public static Optional<String> of(HttpServerRequest r) {
        var a = r.authority();
        if (a == null) {
            return Optional.empty();
        }
        // the Host header's own brackets survive parsing; a bare IPv6 host does not have them
        var host = a.host().contains(":") && !a.host().startsWith("[") ? "[" + a.host() + "]" : a.host();
        return Optional.of(r.scheme() + "://" + host + (a.port() < 0 ? "" : ":" + a.port()));
    }

    /**
     * {@code http} or {@code https}, a host, an optional port, no path, query or fragment; IPv6 only in brackets.
     */
    public static boolean valid(String origin) {
        if (origin == null) {
            return false;
        }
        try {
            var u = new URI(origin);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                    && u.getHost() != null
                    && (u.getRawPath() == null || u.getRawPath().matches("/*"))
                    && u.getRawQuery() == null
                    && u.getRawFragment() == null
                    && u.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
