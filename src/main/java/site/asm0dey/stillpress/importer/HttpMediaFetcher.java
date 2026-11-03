package site.asm0dey.stillpress.importer;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import module java.net.http;

@ApplicationScoped
public class HttpMediaFetcher implements MediaFetcher {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public Optional<byte[]> fetch(URI source) {
        try {
            var res = http.send(
                    HttpRequest.newBuilder(source).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            return res.statusCode() / 100 == 2 ? Optional.of(res.body()) : Optional.empty();
        } catch (IOException e) {
            // unreachable host or timeout counts as missing: reported, not fatal
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching " + source, e);
        }
    }
}
