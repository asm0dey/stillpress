package site.asm0dey.stillpress.verify;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import module java.base;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.verify.ParityReport.Cause;
import site.asm0dey.stillpress.verify.ParityReport.Mismatch;

class UrlParityCheckerTest {
    WireMockServer source = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    WireMockServer target = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    UrlParityChecker checker = new UrlParityChecker(Duration.ZERO, Duration.ZERO);

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
        var sb = new StringBuilder("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (var p : paths) {
            sb.append("<url><loc>").append(s.baseUrl()).append(p).append("</loc></url>");
        }
        return sb.append("</urlset>").toString();
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder xml(String body) {
        return okForContentType("application/xml", body);
    }

    @Test
    void reportsOnlyRealMismatches() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/a/", "/b/", "/?p=1"))));
        source.stubFor(get("/?p=1").willReturn(permanentRedirect("/a/")));
        source.stubFor(get("/a/").willReturn(ok("x")));
        source.stubFor(get("/b/").willReturn(ok("x")));
        target.stubFor(get("/a/").willReturn(ok("x")));
        target.stubFor(get("/b/").willReturn(notFound()));
        target.stubFor(get("/?p=1").willReturn(permanentRedirect("/a/")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.checked()).isEqualTo(5);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/b/");
        assertThat(r.toText()).contains("/b/");
    }

    @Test
    void followsSitemapIndex() {
        source.stubFor(get("/sitemap_index.xml")
            .willReturn(
                    xml(
                            "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><sitemap><loc>"
                            + source.baseUrl()
                            + "/post-sitemap.xml</loc></sitemap></sitemapindex>"
                    )
            )
        );
        source.stubFor(get("/post-sitemap.xml").willReturn(xml(urlset(source, "/c/"))));
        source.stubFor(get("/c/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.checked()).isEqualTo(3);
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
        source.stubFor(get("/q/").willReturn(ok("x")));
        source.stubFor(get("/p/").willReturn(okForContentType("text/html", "<a href='/q/'>q</a>")));
        for (var p : List.of("/", "/p/", "/q/")) {
            target.stubFor(get(p).willReturn(ok("x")));
        }
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.checked()).isEqualTo(5);
        assertThat(r.mismatches()).isEmpty();
        source.verify(0, getRequestedFor(urlPathEqualTo("/wp-admin/x")));
    }

    @Test
    void redirectToDifferentPathIsMismatchAndEncodingCaseIsEqual() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/x/", "/%d0%b0/"))));
        source.stubFor(get("/x/").willReturn(ok("x")));
        source.stubFor(get("/%d0%b0/").willReturn(permanentRedirect("/%d0%b1/")));
        source.stubFor(get("/%d0%b1/").willReturn(ok("x")));
        target.stubFor(get("/x/").willReturn(permanentRedirect("/other/")));
        target.stubFor(get("/other/").willReturn(ok("x")));
        target.stubFor(get("/%d0%b0/").willReturn(permanentRedirect("/%D0%B1/")));
        target.stubFor(get("/%D0%B1/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/x/");
    }

    @Test
    void crossHostRedirectAndLoopsAreMismatches() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/away/", "/loop/"))));
        target.stubFor(get("/away/").willReturn(permanentRedirect("http://elsewhere.invalid/")));
        target.stubFor(get("/loop/").willReturn(permanentRedirect("/loop/")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/away/", "/loop/");
        assertThat(r.mismatches()).allSatisfy(m -> assertThat(m.detail()).isNotBlank());
    }

    @Test
    void networkErrorBecomesMismatch() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/a/"))));
        var dead = URI.create("http://localhost:1");
        var r = checker.check(uri(source), dead, 100);
        assertThat(r.mismatches()).isNotEmpty();
        assertThat(r.mismatches().getFirst().detail()).isNotBlank().doesNotContain("null");
        assertThat(r.mismatches().getFirst().cause()).isEqualTo(Cause.NETWORK_ERROR);
    }

    @Test
    void honoursMaxUrls() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/a/", "/b/", "/c/"))));
        assertThat(checker.check(uri(source), uri(target), 2).checked()).isEqualTo(2);
    }

    @Test
    void sendsBrowserUserAgent() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/a/"))));
        checker.check(uri(source), uri(target), 10);
        source.verify(getRequestedFor(urlEqualTo("/wp-sitemap.xml"))
            .withHeader("User-Agent", containing("Mozilla/5.0"))
        );
        target.verify(getRequestedFor(urlEqualTo("/a/")).withHeader("User-Agent", containing("Mozilla/5.0")));
    }

    @Test
    void bothSidesSameNon200Passes() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/dead/"))));
        source.stubFor(get("/dead/").willReturn(notFound()));
        target.stubFor(get("/dead/").willReturn(notFound()));
        assertThat(checker.check(uri(source), uri(target), 100).mismatches()).isEmpty();
    }

    @Test
    void softOkWhereSourceRedirectsElsewhereIsMismatch() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(xml(urlset(source, "/?p=1", "/old/"))));
        source.stubFor(get("/?p=1").willReturn(permanentRedirect("/a/")));
        source.stubFor(get("/old/").willReturn(permanentRedirect("/new/")));
        source.stubFor(get("/a/").willReturn(ok("x")));
        source.stubFor(get("/new/").willReturn(ok("x")));
        target.stubFor(get("/?p=1").willReturn(ok("x")));
        target.stubFor(get("/old/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).extracting(Mismatch::path).containsExactly("/?p=1", "/old/");
        assertThat(r.mismatches()).allMatch(m -> m.cause() == Cause.DIFFERENT_FINAL_PATH);
    }

    @Test
    void retriesThrottledSourceHonouringRetryAfter() {
        source.stubFor(get("/wp-sitemap.xml")
            .inScenario("t")
            .whenScenarioStateIs(STARTED)
            .willReturn(status(429).withHeader("Retry-After", "0"))
            .willSetStateTo("ok")
        );
        source.stubFor(get("/wp-sitemap.xml")
            .inScenario("t")
            .whenScenarioStateIs("ok")
            .willReturn(xml(urlset(source, "/a/")))
        );
        source.stubFor(get("/a/").willReturn(ok("x")));
        target.stubFor(get("/a/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).isEmpty();
        assertThat(r.checked()).isGreaterThanOrEqualTo(1);
        assertThat(r.ok()).isTrue();
    }

    @Test
    void blockedSourceIsReportedNotSilent() {
        source.stubFor(any(anyUrl()).willReturn(status(403)));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.ok()).isFalse();
        assertThat(r.mismatches())
            .isNotEmpty()
            .allMatch(m -> m.cause() == Cause.SOURCE_FETCH_FAILURE);
        assertThat(r.toText()).contains("source fetch failure");
        source.verify(1, getRequestedFor(urlEqualTo("/wp-sitemap.xml")));
    }

    @Test
    void persistent503IsRetriedFiveTimesThenReported() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(status(503)));
        checker.check(uri(source), uri(target), 100);
        source.verify(5, getRequestedFor(urlEqualTo("/wp-sitemap.xml")));
    }

    @Test
    void badLocDoesNotAbortRestOfSitemap() {
        source.stubFor(get("/wp-sitemap.xml")
            .willReturn(
                    xml(
                            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>http://bad host/a b</loc></url>"
                            + "<url><loc>"
                            + source.baseUrl()
                            + "/good/</loc></url></urlset>"
                    )
            )
        );
        source.stubFor(get("/good/").willReturn(ok("x")));
        target.stubFor(get("/good/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        assertThat(r.mismatches()).hasSize(1);
        assertThat(r.mismatches().getFirst().cause()).isEqualTo(Cause.SOURCE_FETCH_FAILURE);
        assertThat(r.checked()).isGreaterThanOrEqualTo(1);
        target.verify(getRequestedFor(urlEqualTo("/good/")));
    }

    @Test
    void sitemapIgnoresForeignNamespaceLoc() {
        source.stubFor(get("/wp-sitemap.xml")
            .willReturn(
                    xml(

                            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">"
                            + "<url><loc>"
                            + source.baseUrl()
                            + "/a/</loc><image:image><image:loc>"
                            + source.baseUrl()
                            + "/img.jpg</image:loc></image:image></url></urlset>"
                    )
            )
        );
        source.stubFor(get("/a/").willReturn(ok("x")));
        target.stubFor(get("/a/").willReturn(ok("x")));
        checker.check(uri(source), uri(target), 100);
        target.verify(0, getRequestedFor(urlEqualTo("/img.jpg")));
        target.verify(getRequestedFor(urlEqualTo("/a/")));
    }

    @Test
    void checksFeedsSeededAndAdvertised() {
        source.stubFor(get("/")
            .willReturn(
                    okForContentType(
                            "text/html",
                            "<link rel='alternate' type='application/rss+xml' href='/custom-feed/'>"
                    )
            )
        );
        checker.check(uri(source), uri(target), 100);
        for (var p : List.of("/feed/", "/comments/feed/", "/custom-feed/")) {
            target.verify(getRequestedFor(urlEqualTo(p)));
        }
    }

    @Test
    void homeIsCheckedEvenWhenItRedirects() {
        source.stubFor(get("/").willReturn(temporaryRedirect("/home/")));
        source.stubFor(get("/home/").willReturn(okForContentType("text/html", "x")));
        target.stubFor(get("/").willReturn(temporaryRedirect("/home/")));
        target.stubFor(get("/home/").willReturn(ok("x")));
        var r = checker.check(uri(source), uri(target), 100);
        target.verify(getRequestedFor(urlEqualTo("/")));
        assertThat(r.mismatches()).isEmpty();
    }

    @Test
    void sourceIsFetchedOncePerUrl() {
        source.stubFor(get("/").willReturn(okForContentType("text/html", "<a href='/p/'>p</a>")));
        source.stubFor(get("/p/").willReturn(okForContentType("text/html", "x")));
        checker.check(uri(source), uri(target), 100);
        source.verify(1, getRequestedFor(urlEqualTo("/p/")));
        source.verify(1, getRequestedFor(urlEqualTo("/")));
    }

    @Test
    void sameSiteAcceptsWwwAndSchemeVariants() {
        assertThat(UrlParityChecker.sameSite(URI.create("http://example.com/"), URI.create("https://www.example.com/x"))
        )
            .isTrue();
        assertThat(UrlParityChecker.sameSite(URI.create("https://www.example.com"), URI.create("http://example.com"))).isTrue();
        assertThat(UrlParityChecker.sameSite(URI.create("https://example.com"), URI.create("https://other.com"))).isFalse();
        assertThat(UrlParityChecker.sameSite(URI.create("http://localhost:8080"), URI.create("http://localhost:9090")))
            .isFalse();
    }

    @Test
    void reportGroupsByCauseAndPrintsDashForNoStatus() {
        var r = new ParityReport(
                5,
                List.of(
                        new Mismatch("/a/", 200, 404, "target returned 404", Cause.TARGET_404),
                        new Mismatch("/b/", 200, 404, "target returned 404", Cause.TARGET_404),
                        new Mismatch("/c/", 200, -1, "boom", Cause.NETWORK_ERROR)
                )
        );
        var t = r.toText();
        assertThat(t).contains("target 404 (2)").contains("network error (1)").contains("target=-");
        assertThat(t).doesNotContain("-1");
    }

    @Test
    void nonXmlRootSitemapMeansNoSitemapButMalformedXmlFails() {
        source.stubFor(get("/wp-sitemap.xml").willReturn(okForContentType("text/html", "<html>home</html>")));
        source.stubFor(get("/sitemap_index.xml").willReturn(okForContentType("text/plain", "nope")));
        assertThat(checker.check(uri(source), uri(target), 100).mismatches())
            .noneMatch(m -> m.cause() == Cause.SOURCE_FETCH_FAILURE);
        source.stubFor(get("/sitemap_index.xml").willReturn(xml("<urlset><url>")));
        assertThat(checker.check(uri(source), uri(target), 100).mismatches())
            .anyMatch(m -> m.cause() == Cause.SOURCE_FETCH_FAILURE);
    }
}
