package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.content.Permalinker;
import site.asm0dey.stillpress.db.tables.pojos.Term;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ExtendWith(MockitoExtension.class)
class SettingsResourceTest {
    @Mock
    SettingRepository settings;
    @Mock
    TermRepository terms;
    @Mock
    Permalinker permalinks;
    @Mock
    RedirectRepository redirects;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    ImportGuard guard;
    // what the settings table holds: the stubs below read and write it the way the repository does
    final Map<String, String> stored = new HashMap<>();
    SettingsResource resource;

    @BeforeEach
    void setUp() {
        lenient()
            .when(settings.get(anyString()))
            .thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        lenient()
            .doAnswer(i -> stored.put(i.getArgument(0), i.getArgument(1)))
            .when(settings)
            .put(anyString(), anyString());
        lenient()
            .doAnswer(i -> stored.remove(i.<String>getArgument(0)))
            .when(settings)
            .delete(anyString());
        stored.putAll(Map.of(
                "title",
                "Blog",
                "language",
                "en-GB",
                "home_url",
                "http://localhost:8081",
                "timezone",
                "Europe/Moscow"
        ));
        resource = new SettingsResource(settings, terms, permalinks, redirects, queue, jobs, guard);
    }

    /**
     * What the form sends back unchanged.
     */
    private SettingsResource.Settings current() {
        return resource.get();
    }

    private static SettingsResource.Settings with(SettingsResource.Settings s, String field, Object value) {
        return new SettingsResource.Settings(
                "title".equals(field) ? (String) value : s.title(),
                "description".equals(field) ? (String) value : s.description(),
                "language".equals(field) ? (String) value : s.language(),
                "homeUrl".equals(field) ? (String) value : s.homeUrl(),
                "adminUrl".equals(field) ? (String) value : s.adminUrl(),
                "timezone".equals(field) ? (String) value : s.timezone(),
                "postsPerPage".equals(field) ? (Integer) value : s.postsPerPage(),
                "postsPerRss".equals(field) ? (Integer) value : s.postsPerRss(),
                "permalinkStructure".equals(field) ? (String) value : s.permalinkStructure(),
                "categoryBase".equals(field) ? (String) value : s.categoryBase(),
                "tagBase".equals(field) ? (String) value : s.tagBase(),
                "defaultCategory".equals(field) ? (Long) value : s.defaultCategory(),
                "defaultCommentStatus".equals(field) ? (String) value : s.defaultCommentStatus()
        );
    }

    private static Term term(long id, String taxonomy, String slug) {
        return new Term(id, null, taxonomy, slug, slug, null, "");
    }

    @Test
    void getReturnsDefaultsOnAFreshStore() {
        stored.clear();
        assertThat(resource.get())
            .isEqualTo(
                    new SettingsResource.Settings(
                            "",
                            "",
                            "en",
                            "",
                            "",
                            "UTC",
                            10,
                            10,
                            "/%year%/%monthnum%/%day%/%postname%/",
                            "category",
                            "tag",
                            null,
                            "open"
                    )
            );
    }

    @Test
    void getReturnsWhatIsStored() {
        stored.put("posts_per_page", "25");
        stored.put("default_category", "5");
        stored.put("admin_url", "https://admin.example.org");
        var s = resource.get();
        assertThat(s.postsPerPage()).isEqualTo(25);
        assertThat(s.defaultCategory()).isEqualTo(5L);
        assertThat(s.adminUrl()).isEqualTo("https://admin.example.org");
        assertThat(s.timezone()).isEqualTo("Europe/Moscow");
    }

    @Test
    void invalidFieldsAre400OnTheFieldAndNothingIsWrittenOrClosed() {
        var cases = new LinkedHashMap<String, Object>();
        cases.put("title", " ");
        cases.put("language", "Russian");
        cases.put("homeUrl", "https://blog.example.org/sub");
        cases.put("adminUrl", "https://admin.example.org/x");
        cases.put("timezone", "Mars/Base");
        cases.put("postsPerPage", 0);
        cases.put("postsPerRss", 101);
        cases.put("permalinkStructure", "/%year%/");
        cases.put("categoryBase", "Two Words");
        cases.put("tagBase", "category");
        cases.put("defaultCategory", 424242L);
        cases.put("defaultCommentStatus", "maybe");
        cases.forEach((field, value) -> {
            try (var r = resource.put(with(current(), field, value))) {
                assertThat(r.getStatus()).as(field).isEqualTo(400);
                assertThat(r.getEntity()).as(field).isEqualTo(Map.of("error", "invalid", "field", field));
            }
        });
        verifyNoInteractions(guard, queue, jobs, redirects);
        verify(settings, never()).put(anyString(), anyString());
    }

    @Test
    void aMissingBodyOrCommentStatusIs400NotAnError() {
        try (var r = resource.put(null)) {
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "title"));
        }
        try (var r = resource.put(with(current(), "defaultCommentStatus", null))) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "defaultCommentStatus"));
        }
    }

    @Test
    void permalinkStructureNeedsSlashesAtBothEndsAndAPostToken() {
        for (var bad : List.of("%postname%/", "/%post_id%", "/archive/")) {
            try (var r = resource.put(with(current(), "permalinkStructure", bad))) {
                assertThat(r.getEntity()).as(bad).isEqualTo(Map.of("error", "invalid", "field", "permalinkStructure"));
            }
        }
    }

    @Test
    void aDefaultCategoryMustBeACategoryTerm() {
        when(terms.findById(7)).thenReturn(Optional.of(term(7, "post_tag", "kids")));
        try (var r = resource.put(with(current(), "defaultCategory", 7L))) {
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid", "field", "defaultCategory"));
        }
    }

    @Test
    void aSaveClosesImportAndStoresEveryFieldThenAnswersTheStoredSettings() {
        when(terms.findById(5)).thenReturn(Optional.of(term(5, "category", "news")));
        var body = with(
                with(with(current(), "defaultCategory", 5L), "defaultCommentStatus", "closed"),
                "adminUrl",
                "https://admin.example.org/"
        );
        try (var r = resource.put(body)) {
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getEntity())
                .isEqualTo(
                        with(
                                with(with(current(), "defaultCategory", 5L), "defaultCommentStatus", "closed"),
                                "adminUrl",
                                "https://admin.example.org"
                        )
                );
        }
        verify(guard).close();
        assertThat(stored)
            .containsEntry("default_category", "5")
            .containsEntry("default_comment_status", "closed")
            .containsEntry("admin_url", "https://admin.example.org");
    }

    @Test
    void anEmptyAdminUrlAndANullDefaultCategoryDeleteTheirSettings() {
        stored.put("admin_url", "https://admin.example.org");
        stored.put("default_category", "5");
        try (var r = resource.put(with(with(current(), "adminUrl", ""), "defaultCategory", null))) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        assertThat(stored).doesNotContainKeys("admin_url", "default_category");
    }

    @Test
    void trailingSlashesOfTheHomeUrlAreDropped() {
        try (var r = resource.put(with(current(), "homeUrl", "http://localhost:8081/"))) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        assertThat(stored).containsEntry("home_url", "http://localhost:8081");
        verifyNoInteractions(queue, jobs);
    }

    @Test
    void changingWhatEveryPageShowsRepublishesTheWholeSite() {
        for (var change :
                List.of(
                        with(current(), "title", "New name"),
                        with(current(), "description", "About"),
                        with(current(), "language", "ru"),
                        with(current(), "homeUrl", "https://blog.example.org"),
                        with(current(), "postsPerPage", 20),
                        with(current(), "postsPerRss", 20)
        )) {
            stored.clear();
            stored.putAll(Map.of(
                    "title",
                    "Blog",
                    "language",
                    "en-GB",
                    "home_url",
                    "http://localhost:8081",
                    "timezone",
                    "Europe/Moscow"
            ));
            try (var r = resource.put(change)) {
                assertThat(r.getStatus()).isEqualTo(200);
            }
        }
        verify(queue, org.mockito.Mockito.times(6)).all();
        verify(jobs, org.mockito.Mockito.times(6)).requestPublish();
    }

    @Test
    void aChangeOfOnlyTheAdminUrlTimezoneOrCommentStatusRepublishesNothing() {
        try (var r = resource.put(
                with(
                        with(with(current(), "adminUrl", "https://admin.example.org"), "timezone", "UTC"),
                        "defaultCommentStatus",
                        "closed"
                )
        )) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        verifyNoInteractions(queue, jobs);
        verify(redirects).putAll(Map.of());
    }

    @Test
    void aNewCategoryBaseRedirectsTheBaseAndEveryTermArchiveThenRepublishes() {
        when(terms.byTaxonomy("category")).thenReturn(List.of(
                term(1, "category", "travel"),
                term(2, "category", "europe")
        ));
        when(permalinks.termPath(1)).thenReturn("travel");
        when(permalinks.termPath(2)).thenReturn("travel/europe");
        try (var r = resource.put(with(current(), "categoryBase", "topics"))) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        var moves = new LinkedHashMap<String, String>();
        moves.put("/category/", "/topics/");
        moves.put("/category/travel/", "/topics/travel/");
        moves.put("/category/travel/europe/", "/topics/travel/europe/");
        verify(redirects).putAll(moves);
        var order = inOrder(queue, jobs);
        order.verify(queue).all();
        order.verify(jobs).requestPublish();
    }

    @Test
    void aNewTagBaseRedirectsTheTagArchivesBySlug() {
        when(terms.byTaxonomy("post_tag")).thenReturn(List.of(term(3, "post_tag", "kids")));
        try (var r = resource.put(with(current(), "tagBase", "labels"))) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        verify(redirects).putAll(Map.of("/tag/", "/labels/", "/tag/kids/", "/labels/kids/"));
        verify(queue).all();
    }
}
