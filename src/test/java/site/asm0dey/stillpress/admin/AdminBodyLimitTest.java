package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.Test;

/**
 * Anonymous admin API requests are answered before a large body is read: each request here announces 100 MB and
 * sends none of it, so a server that waited for the body would time out.
 */
@QuarkusTest
class AdminBodyLimitTest {
    private static final long HUNDRED_MB = 100L * 1024 * 1024;
    @TestHTTPResource("/")
    URL app;
    @Inject
    Vertx vertx;

    @Test
    void largeJsonBodiesAre413() throws IOException {
        for (var path : List.of("/admin/api/login", "/admin/api/set-password", "/admin/api/setup")) {
            assertThat(status(path, "Content-Type: application/json\r\nContent-Length: " + HUNDRED_MB)).as(path).isEqualTo(
                    413
            );
        }
    }

    @Test
    void chunkedJsonBodiesAre411() throws IOException {
        assertThat(status("/admin/api/login", "Content-Type: application/json\r\nTransfer-Encoding: chunked")).isEqualTo(
                411
        );
    }

    @Test
    void h2cBodyWithoutLengthIsRefused() throws Exception {
        var chunk = Buffer.buffer("x".repeat(50 * 1024));
        assertThat(h2c(HttpMethod.POST, "/admin/api/login", chunk)).isEqualTo(411);
    }

    @Test
    void h2cGetWithoutBodyIs200() throws Exception {
        assertThat(h2c(HttpMethod.GET, "/admin/api/setup", null)).isEqualTo(200);
    }

    /**
     * One request over HTTP/2 prior knowledge; a body is written as two chunks, so no Content-Length is known.
     */
    private int h2c(HttpMethod method, String path, Buffer chunk) throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
            .setProtocolVersion(HttpVersion.HTTP_2)
            .setHttp2ClearTextUpgrade(false)
        );
        try {
            return client
                .request(method, app.getPort(), app.getHost(), path)
                .compose(r -> {
                    if (chunk == null) {
                        return r.send();
                    }
                    r.putHeader("Content-Type", "application/json").setChunked(true);
                    // the server answers 411 and resets the stream, so a write may fail; the response is what counts
                    r.write(chunk);
                    r.write(chunk);
                    r.end();
                    return r.response();
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .statusCode();
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anonymousImportUploadIs401() throws IOException {
        assertThat(
                status(
                        "/admin/api/import",
                        "Content-Type: multipart/form-data; boundary=x\r\nContent-Length: " + HUNDRED_MB
                )
        )
            .isEqualTo(401);
    }

    @Test
    void anonymousMediaUploadIs401() throws IOException {
        assertThat(
                status(
                        "/admin/api/media",
                        "Content-Type: multipart/form-data; boundary=x\r\nContent-Length: " + HUNDRED_MB
                )
        )
            .isEqualTo(401);
    }

    private int status(String path, String headers) throws IOException {
        try (var s = new Socket(app.getHost(), app.getPort())) {
            s.setSoTimeout(5000);
            var out = s.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\n" + headers + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII)
            );
            out.flush();
            var line =
                    new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }
}
