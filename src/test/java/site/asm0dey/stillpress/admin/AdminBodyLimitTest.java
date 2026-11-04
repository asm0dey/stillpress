package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
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
    void anonymousImportUploadIs401() throws IOException {
        assertThat(
                status(
                        "/admin/api/import",
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
