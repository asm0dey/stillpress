package site.asm0dey.stillpress.importer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpMediaFetcherTest {
    WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeEach
    void start() {
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void followsRedirectsAndReportsMissing() {
        wm.stubFor(get("/old.jpg").willReturn(permanentRedirect("/a.jpg")));
        wm.stubFor(get("/a.jpg")
            .withHeader("User-Agent", containing("Mozilla/5.0"))
            .willReturn(ok().withBody(new byte[] {7, 8}))
        );
        wm.stubFor(get("/gone.jpg").willReturn(notFound()));
        var f = new HttpMediaFetcher();
        assertThat(f.fetch(URI.create(wm.url("/old.jpg")))).hasValueSatisfying(b -> assertThat(b).containsExactly(7, 8));
        assertThat(f.fetch(URI.create(wm.url("/gone.jpg")))).isEmpty();
    }
}
