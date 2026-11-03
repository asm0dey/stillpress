package site.asm0dey.stillpress.verify;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.verify.ParityReport.Mismatch;

class UrlParityCheckerTest {
    WireMockServer source = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    WireMockServer target = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    UrlParityChecker checker = new UrlParityChecker(Duration.ZERO);

    @BeforeEach
    void start() {
        source.start();
        target.start();
    }

    @AfterEach
    void stop() {
        source.stop();
        target.stop();
    }

    URI uri(WireMockServer s) {
        return URI.create(s.baseUrl());
    }

    String urlset(WireMockServer s, String... paths) {
        var sb = new StringBuilder("<urlset>");
        for (var p : paths) {
            sb.append("<url><loc>").append(s.baseUrl()).append(p).append("</loc></url>");
        }
        return sb.append("</urlset>").toString();
    }

    @Test
    void reportsOnlyRealMismatches() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(ok(urlset(source, "/a/", "/b/", "/?p=1"))));
        source.stubFor(get("/?p=1").willReturn(permanentRedirect("/a/")));
        source.stubFor(get("/a/").willReturn(ok("x")));
        source.stubFor(get("/b/").willReturn(ok("x")));
        target.stubFor(get("/a/").willReturn(ok("x")));
        target.stubFor(get("/b/").willReturn(notFound()));
        target.stubFor(get("/?p=1").willReturn(permanentRedirect("/a/")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/b/");
        assertThat(r.toText()).contains("/b/");
    }

    @Test
    void followsSitemapIndex() {
        source.stubFor(get("/sitemap_index.xml")
            .willReturn(
                    ok(
                            "<sitemapindex><sitemap><loc>" + source.baseUrl() + "/post-sitemap.xml</loc></sitemap></sitemapindex>"
                    )
            )
        );
        source.stubFor(get("/post-sitemap.xml").willReturn(ok(urlset(source, "/c/"))));
        source.stubFor(get("/c/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.checked()).isEqualTo(1);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/c/");
        assertThat(r.mismatches().getFirst().targetStatus()).isEqualTo(404);
    }

    @Test
    void crawlsHomeLinksAndSkipsJunk() {
        source.stubFor(get("/")
            .willReturn(
                    okForContentType(
                            "text/html",
                            "<a href='/p/'>p</a><a href='/wp-admin/x'>a</a><a href='#top'>t</a><a href='mailto:a@b.c'>m</a>"
                            + "<a href='http://elsewhere.invalid/z'>z</a><a href='/p/#frag'>again</a>"
                    )
            )
        );
        source.stubFor(get("/p/").willReturn(okForContentType("text/html", "<a href='/q/'>q</a>")));
        for (var p : List.of("/", "/p/", "/q/")) {
            target.stubFor(get(p).willReturn(ok("x")));
        }
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.mismatches()).isEmpty();
        source.verify(0, getRequestedFor(urlPathEqualTo("/wp-admin/x")));
    }

    @Test
    void redirectToDifferentPathIsMismatchAndEncodingCaseIsEqual() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(ok(urlset(source, "/x/", "/%d0%b0/"))));
        source.stubFor(get("/x/").willReturn(ok("x")));
        source.stubFor(get("/%d0%b0/").willReturn(permanentRedirect("/%d0%b1/")));
        target.stubFor(get("/x/").willReturn(permanentRedirect("/other/")));
        target.stubFor(get("/other/").willReturn(ok("x")));
        target.stubFor(get("/%d0%b0/").willReturn(permanentRedirect("/%D0%B1/")));
        target.stubFor(get("/%D0%B1/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/x/");
    }

    @Test
    void crossHostRedirectAndLoopsAreMismatches() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(ok(urlset(source, "/away/", "/loop/"))));
        target.stubFor(get("/away/").willReturn(permanentRedirect("http://elsewhere.invalid/")));
        target.stubFor(get("/loop/").willReturn(permanentRedirect("/loop/")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/away/", "/loop/");
        assertThat(r.mismatches()).allSatisfy(m -> assertThat(m.detail()).isNotBlank());
    }

    @Test
    void networkErrorBecomesMismatch() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(ok(urlset(source, "/a/"))));
        var dead = URI.create("http://localhost:1");
        var r = checker.check(uri(source), dead, 100);
        assertThat(r.mismatches()).hasSize(1);
        assertThat(r.mismatches().getFirst().detail()).isNotBlank();
    }

    @Test
    void honoursMaxUrls() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(ok(urlset(source, "/a/", "/b/", "/c/"))));
        assertThat(checker.check(uri(source), uri(target), 2).checked()).isEqualTo(2);
    }

    @Test
    void sendsBrowserUserAgent() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(ok(urlset(source, "/a/"))));
        checker.check(uri(source), uri(target), 10);
        source.verify(getRequestedFor(urlEqualTo("/wp-sitemap.xml"))
            .withHeader("User-Agent", containing("Mozilla/5.0"))
        );
        target.verify(getRequestedFor(urlEqualTo("/a/")).withHeader("User-Agent", containing("Mozilla/5.0")));
    }
}
