package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import module java.base;
import org.junit.jupiter.api.Test;

class OriginsTest {
    @Test
    void ipv6HostIsBracketed() {
        assertThat(Origins.of(request(HostAndPort.create("::1", 8080)))).contains("http://[::1]:8080");
    }

    @Test
    void noAuthorityIsEmpty() {
        assertThat(Origins.of(request(null))).isEmpty();
    }

    @Test
    void validRejectsPathAndBareIpv6() {
        assertThat(Origins.valid("https://a.example/x")).isFalse();
        assertThat(Origins.valid("http://::1")).isFalse();
        assertThat(Origins.valid("http://[::1]:8080")).isTrue();
    }

    private static HttpServerRequest request(HostAndPort authority) {
        return (HttpServerRequest) java.lang.reflect.Proxy.newProxyInstance(OriginsTest.class.getClassLoader(), new Class<?>[] {
                HttpServerRequest.class
        }, (p, m, a) -> "scheme".equals(m.getName()) ? "http" : authority);
    }
}
