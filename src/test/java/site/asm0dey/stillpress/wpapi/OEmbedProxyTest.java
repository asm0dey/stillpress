package site.asm0dey.stillpress.wpapi;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OEmbedProxyTest {
    WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    OEmbedProxy proxy;

    @BeforeEach
    void start() {
        wm.start();
        var endpoint = wm.baseUrl() + "/oembed?format=json&url=";
        proxy = new OEmbedProxy(Map.of("youtube.com", endpoint, "youtu.be", endpoint, "vimeo.com", endpoint));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void metadataAddressIsRefusedWithoutFetching() {
        wm.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        var r = proxy.fetch("http://169.254.169.254/");
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(r.getEntity()).isEqualTo(Map.of("code", "oembed_invalid_url"));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void lookAlikeHostIsRefused() {
        wm.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        assertThat(proxy.fetch("https://youtube.com.evil.example/watch?v=x").getStatus()).isEqualTo(404);
        assertThat(proxy.fetch("https://evilyoutube.com/watch?v=x").getStatus()).isEqualTo(404);
        assertThat(proxy.fetch("http://www.youtube.com/watch?v=x").getStatus()).isEqualTo(404);
        assertThat(proxy.fetch("not a url").getStatus()).isEqualTo(404);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void youtubeIsProxied() {
        var body = "{\"type\":\"video\",\"html\":\"<iframe></iframe>\"}";
        wm.stubFor(get(urlPathEqualTo("/oembed"))
            .withQueryParam("url", equalTo("https://www.youtube.com/watch?v=x"))
            .willReturn(okJson(body))
        );
        var r = proxy.fetch("https://www.youtube.com/watch?v=x");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getEntity()).isEqualTo(body);
    }

    @Test
    void providerErrorIs404() {
        wm.stubFor(get(urlPathEqualTo("/oembed")).willReturn(notFound()));
        assertThat(proxy.fetch("https://vimeo.com/1").getStatus()).isEqualTo(404);
    }
}
