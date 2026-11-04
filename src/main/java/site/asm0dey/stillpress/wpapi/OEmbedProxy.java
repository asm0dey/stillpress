package site.asm0dey.stillpress.wpapi;

import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import module java.base;
import module java.net.http;

/**
 * The editor's embed preview ({@code GET /admin/api/oembed}): only {@code https} URLs on an allow-listed provider
 * host (exactly, or a subdomain of it) are looked up; anything else is refused without an outbound request.
 */
@Singleton
public final class OEmbedProxy {
    private static final String YOUTUBE = "https://www.youtube.com/oembed?format=json&url=";
    private static final Map<String, String> PROVIDERS =
            Map.of("youtube.com", YOUTUBE, "youtu.be", YOUTUBE, "vimeo.com", "https://vimeo.com/api/oembed.json?url=");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final Map<String, String> endpoints;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    public OEmbedProxy() {
        this(PROVIDERS);
    }

    /**
     * @param endpoints allow-listed host to the provider's oEmbed endpoint, which the encoded URL is appended to
     */
    OEmbedProxy(Map<String, String> endpoints) {
        this.endpoints = endpoints;
    }

    public Response fetch(String url) {
        var endpoint = endpointFor(url);
        if (endpoint == null) {
            return invalid();
        }
        try {
            var res = http.send(
                    HttpRequest
                        .newBuilder(URI.create(endpoint + URLEncoder.encode(url, StandardCharsets.UTF_8)))
                        .timeout(TIMEOUT)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            return res.statusCode() == 200
                    ? Response.ok(res.body(), MediaType.APPLICATION_JSON_TYPE).build()
                    : invalid();
        } catch (IOException e) {
            return invalid();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invalid();
        }
    }

    private String endpointFor(String url) {
        URI u;
        try {
            u = new URI(Objects.requireNonNullElse(url, ""));
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
            return null;
        }
        var host = u.getHost().toLowerCase(Locale.ROOT);
        return endpoints
            .entrySet()
            .stream()
            .filter(e -> host.equals(e.getKey()) || host.endsWith("." + e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private static Response invalid() {
        return Response
            .status(404)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(Map.of("code", "oembed_invalid_url"))
            .build();
    }
}
