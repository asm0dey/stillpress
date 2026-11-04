package site.asm0dey.stillpress.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.content.Permalinker;
import site.asm0dey.stillpress.content.PermalinkStructure;
import site.asm0dey.stillpress.content.Slugs;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.RedirectRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

/**
 * The administrator's site settings. A change to what every page shows republishes the whole site; a new permalink
 * structure applies only to items published afterwards.
 */
@Path("/admin/api/settings")
@RolesAllowed("administrator")
public class SettingsResource {
    @Inject
    SettingRepository settings;
    @Inject
    TermRepository terms;
    @Inject
    Permalinker permalinks;
    @Inject
    RedirectRepository redirects;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;
    @Inject
    ImportGuard guard;

    public record Settings(
            String title,
            String description,
            String language,
            String homeUrl,
            String timezone,
            Integer postsPerPage,
            Integer postsPerRss,
            String permalinkStructure,
            String categoryBase,
            String tagBase,
            Long defaultCategory,
            String defaultCommentStatus
    ) {}

    @GET
    public Settings get() {
        return new Settings(
                value("title", ""),
                value("description", ""),
                value("language", "en"),
                value("home_url", ""),
                value("timezone", "UTC"),
                Integer.valueOf(value("posts_per_page", "10")),
                Integer.valueOf(value("posts_per_rss", "10")),
                value("permalink_structure", PermalinkStructure.DEFAULT),
                value("category_base", "category"),
                value("tag_base", "tag"),
                settings.get("default_category").map(Long::valueOf).orElse(null),
                value("default_comment_status", "open")
        );
    }

    @PUT
    @Transactional
    public Response put(Settings s) {
        guard.close();
        var field = invalidField(s);
        if (field != null) {
            return Response.status(400).entity(Map.of("error", "invalid", "field", field)).build();
        }
        var before = get();
        var home = s.homeUrl().replaceAll("/+$", "");
        settings.put("title", s.title());
        settings.put("description", Objects.requireNonNullElse(s.description(), ""));
        settings.put("language", s.language());
        settings.put("home_url", home);
        settings.put("timezone", s.timezone());
        settings.put("posts_per_page", String.valueOf(s.postsPerPage()));
        settings.put("posts_per_rss", String.valueOf(s.postsPerRss()));
        settings.put("permalink_structure", s.permalinkStructure());
        settings.put("category_base", s.categoryBase());
        settings.put("tag_base", s.tagBase());
        if (s.defaultCategory() == null) {
            settings.delete("default_category");
        } else {
            settings.put("default_category", String.valueOf(s.defaultCategory()));
        }
        settings.put("default_comment_status", s.defaultCommentStatus());
        var moves = new LinkedHashMap<String, String>();
        moveBase(moves, before.categoryBase(), s.categoryBase(), "category");
        moveBase(moves, before.tagBase(), s.tagBase(), "post_tag");
        redirects.putAll(moves);
        if (changesEveryPage(before, s, home) || !moves.isEmpty()) {
            queue.all();
            jobs.requestPublish();
        }
        return Response.ok(get()).build();
    }

    private static boolean changesEveryPage(Settings was, Settings now, String home) {
        return !was.title().equals(now.title())
                || !was.description().equals(Objects.requireNonNullElse(now.description(), ""))
                || !was.language().equals(now.language())
                || !was.homeUrl().equals(home)
                || !was.postsPerPage().equals(now.postsPerPage())
                || !was.postsPerRss().equals(now.postsPerRss());
    }

    /**
     * 301s from the old archive base, its terms included, to the new one.
     */
    private void moveBase(Map<String, String> moves, String from, String to, String taxonomy) {
        if (from.equals(to)) {
            return;
        }
        moves.put("/" + from + "/", "/" + to + "/");
        for (var t : terms.byTaxonomy(taxonomy)) {
            var path = "category".equals(taxonomy) ? permalinks.termPath(t.getId()) : t.getSlug();
            moves.put("/" + from + "/" + path + "/", "/" + to + "/" + path + "/");
        }
    }

    /**
     * The first bad field in form order, or {@code null}.
     */
    private String invalidField(Settings s) {
        if (s == null || s.title() == null || s.title().isBlank()) {
            return "title";
        }
        if (s.language() == null || !SetupResource.LANGUAGE.matcher(s.language()).matches()) {
            return "language";
        }
        if (!validHomeUrl(s.homeUrl())) {
            return "homeUrl";
        }
        if (!validZone(s.timezone())) {
            return "timezone";
        }
        if (!inRange(s.postsPerPage())) {
            return "postsPerPage";
        }
        if (!inRange(s.postsPerRss())) {
            return "postsPerRss";
        }
        var p = s.permalinkStructure();
        if (p == null
                || !p.startsWith("/")
                || !p.endsWith("/")
                || !(p.contains("%postname%") || p.contains("%post_id%"))) {
            return "permalinkStructure";
        }
        if (!slug(s.categoryBase())) {
            return "categoryBase";
        }
        if (!slug(s.tagBase()) || s.tagBase().equals(s.categoryBase())) {
            return "tagBase";
        }
        if (s.defaultCategory() != null
                && terms.findById(s.defaultCategory()).filter(t -> "category".equals(t.getTaxonomy())).isEmpty()) {
            return "defaultCategory";
        }
        if (!Set.of("open", "closed").contains(s.defaultCommentStatus())) {
            return "defaultCommentStatus";
        }
        return null;
    }

    private static boolean validHomeUrl(String v) {
        if (v == null) {
            return false;
        }
        try {
            var u = new URI(v);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                    && u.getHost() != null
                    && (u.getPath() == null || u.getPath().matches("/*"))
                    && u.getRawQuery() == null
                    && u.getRawFragment() == null
                    && u.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean validZone(String v) {
        try {
            ZoneId.of(v);
            return true;
        } catch (DateTimeException | NullPointerException e) {
            return false;
        }
    }

    private static boolean inRange(Integer n) {
        return n != null && n >= 1 && n <= 100;
    }

    private static boolean slug(String v) {
        return v != null && !v.isEmpty() && Slugs.title(v).equals(v);
    }

    private String value(String key, String fallback) {
        return settings.get(key).orElse(fallback);
    }
}
