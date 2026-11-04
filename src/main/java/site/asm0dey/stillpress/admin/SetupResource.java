package site.asm0dey.stillpress.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SessionAuthMechanism;
import site.asm0dey.stillpress.publish.SitePublisher;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The first-run wizard: names the site, creates the first administrator and publishes the empty site.
 */
@Path("/admin/api/setup")
public class SetupResource {
    private static final Logger LOG = Logger.getLogger(SetupResource.class);
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Z]{2})?$");
    private static final Map<String, String> DEFAULTS =
            Map.of("posts_per_page", "10", "posts_per_rss", "10", "category_base", "category", "tag_base", "tag");
    // one install = one process; held across the commit so a second caller sees the first administrator
    private static final ReentrantLock LOCK = new ReentrantLock();
    @Inject
    SetupToken token;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    SitePublisher publisher;
    @Context
    HttpServerRequest request;

    public record Setup(
            String token,
            String title,
            String language,
            String login,
            String email,
            String displayName,
            String password
    ) {}

    @GET
    public Map<String, Boolean> status() {
        return Map.of("required", !users.anyAdministratorWithPassword());
    }

    @POST
    public Response setup(Setup s) {
        String raw;
        LOCK.lock();
        try {
            if (users.anyAdministratorWithPassword()) {
                return error(409, Map.of("error", "already_set_up"));
            }
            if (s == null || !token.matches(s.token())) {
                return error(403, Map.of("error", "bad_token"));
            }
            var field = invalidField(s);
            if (field != null) {
                return error(400, Map.of("error", "invalid", "field", field));
            }
            var hash = Passwords.hash(s.password());
            // scheme and authority are the client's when a trusted proxy forwarded them
            var a = request.authority();
            var home = request.scheme() + "://" + a.host() + (a.port() < 0 ? "" : ":" + a.port());
            raw = QuarkusTransaction
                .requiringNew()
                .call(() -> create(s, hash, home));
            token.discard();
        } finally {
            LOCK.unlock();
        }
        try {
            publisher.publishAll();
        } catch (RuntimeException e) {
            // the site exists and the administrator can log in; a later publish retries this
            LOG.error("Publishing the new site failed", e);
        }
        var cookie = SessionAuthMechanism.cookie(raw, "https".equals(request.scheme()));
        return Response.status(201).header(HttpHeaders.SET_COOKIE, cookie.encode()).build();
    }

    private String create(Setup s, String hash, String home) {
        settings.put("title", s.title().strip());
        settings.put("language", s.language());
        // an install that already holds a CLI import keeps the export's site URL, not the host the admin used
        if (settings.get("home_url").isEmpty()) {
            settings.put("home_url", home);
        }
        DEFAULTS.forEach((k, v) -> {
            if (settings.get(k).isEmpty()) {
                settings.put(k, v);
            }
        });
        // an imported author with this login becomes the administrator instead of colliding with it
        var id = users
            .findByLoginOrEmail(s.login())
            .filter(u -> u.getLogin().equals(s.login()))
            .map(u -> {
                users.setRole(u.getId(), Role.ADMINISTRATOR);
                return u.getId();
            })
            .orElseGet(() -> users.create(s.login(), s.email(), s.displayName(), Role.ADMINISTRATOR).getId());
        users.setPassword(id, hash);
        return sessions.create(id);
    }

    /**
     * The first bad field in form order, or {@code null}; lengths follow the {@code author} columns.
     */
    private static String invalidField(Setup s) {
        if (blank(s.title())) {
            return "title";
        }
        if (s.language() == null || !LANGUAGE.matcher(s.language()).matches()) {
            return "language";
        }
        if (blank(s.login()) || !s.login().equals(s.login().strip()) || s.login().length() > 60) {
            return "login";
        }
        if (blank(s.email()) || s.email().indexOf('@') < 1 || s.email().length() > 100) {
            return "email";
        }
        if (blank(s.displayName()) || s.displayName().length() > 250) {
            return "displayName";
        }
        try {
            Passwords.validate(s.password());
        } catch (IllegalArgumentException e) {
            return "password";
        }
        return null;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static Response error(int status, Map<String, String> body) {
        return Response.status(status).entity(body).build();
    }
}
