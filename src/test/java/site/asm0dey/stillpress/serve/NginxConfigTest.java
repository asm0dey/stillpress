package site.asm0dey.stillpress.serve;

import static org.assertj.core.api.Assertions.assertThat;
import module java.base;
import org.junit.jupiter.api.Test;

class NginxConfigTest {
    @Test
    void includesRootFallbackAndDynamicPrefixes() {
        var c = NginxConfig.render(Path.of("/srv/sp/output/current"), "127.0.0.1:8080");
        assertThat(c)
            .contains("root /srv/sp/output/current;")
            .contains("try_files $uri @stillpress;")
            .contains("try_files ${uri}index.html @stillpress;")
            .contains("location ^~ /admin")
            .contains("location = /wp-login.php")
            .contains("application/rss+xml")
            .doesNotContain("$uri/ ");
    }
}
