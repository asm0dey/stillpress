package site.asm0dey.stillpress.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.account.Captcha;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SessionAuthMechanism;
import site.asm0dey.stillpress.account.UserFields;
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The first-run wizard: names the site, creates the first administrator and publishes the empty site.
 */
@Path("/admin/api/setup")
public class SetupResource {
    static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Z]{2})?$");
    // one install = one process; held across the commit so a second caller sees the first administrator
    private static final ReentrantLock LOCK = new ReentrantLock();
    private final SetupToken token;
    private final Captcha captcha;
    private final AuthorRepository users;
    private final SessionRepository sessions;
    private final SettingRepository settings;
    private final PublishQueue queue;
    private final Jobs jobs;
    private final ImportGuard guard;
    @Context
    HttpServerRequest request;

    SetupResource(
            SetupToken token,
            Captcha captcha,
            AuthorRepository users,
            SessionRepository sessions,
            SettingRepository settings,
            PublishQueue queue,
            Jobs jobs,
            ImportGuard guard
    ) {
        this.token = token;
        this.captcha = captcha;
        this.users = users;
        this.sessions = sessions;
        this.settings = settings;
        this.queue = queue;
        this.jobs = jobs;
        this.guard = guard;
    }

    public record Setup(
            String token,
            String title,
            String language,
            String login,
            String email,
            String displayName,
            String password,
            String timezone,
            String captcha,
            String website
    ) {}

    @GET
    public Map<String, Boolean> status() {
        return Map.of("required", !users.anyAdministratorWithPassword());
    }

    @POST
    public Response setup(Setup sent) {
        String raw;
        LOCK.lock();
        try {
            if (users.anyAdministratorWithPassword()) {
                return error(409, Map.of("error", "already_set_up"));
            }
            if (sent == null || !token.matches(sent.token())) {
                return error(403, Map.of("error", "bad_token"));
            }
            captcha.verify(sent.captcha(), sent.website(), request.remoteAddress().hostAddress());
            var s = new Setup(
                    sent.token(),
                    sent.title(),
                    sent.language(),
                    UserFields.trim(sent.login()),
                    UserFields.trim(sent.email()),
                    UserFields.trim(sent.displayName()),
                    sent.password(),
                    sent.timezone(),
                    sent.captcha(),
                    sent.website()
            );
            var field = invalidField(s);
            if (field != null) {
                return error(400, Map.of("error", "invalid", "field", field));
            }
            // an upgraded imported author keeps its row; anyone else's email is taken
            var upgraded = users
                .findByLogin(s.login())
                .map(u -> u.getId())
                .orElse(-1L);
            if (users.emailTaken(s.email(), upgraded)) {
                return error(409, Map.of("error", "email_taken"));
            }
            var hash = Passwords.hash(s.password());
            // scheme and authority are the client's when a trusted proxy forwarded them
            var home = Origins.of(request);
            if (home.isEmpty()) {
                return error(400, Map.of("error", "no_host"));
            }
            var origin = home.get();
            raw = QuarkusTransaction
                .requiringNew()
                .call(() -> {
                    var session = create(s, hash, origin);
                    // published in the background, once this commits
                    queue.all();
                    jobs.requestPublish();
                    return session;
                });
            token.discard();
        } finally {
            LOCK.unlock();
        }
        var cookie = SessionAuthMechanism.cookie(raw, "https".equals(request.scheme()));
        return Response.status(201).header(HttpHeaders.SET_COOKIE, cookie.encode()).build();
    }

    /**
     * The wizard's "Start empty": the site will never be imported.
     */
    @POST
    @Path("start-empty")
    @RolesAllowed("administrator")
    @Transactional
    public Response startEmpty() {
        guard.close();
        return Response.noContent().build();
    }

    private String create(Setup s, String hash, String home) {
        settings.put("title", s.title().strip());
        settings.put("language", s.language());
        if (s.timezone() != null) {
            settings.put("timezone", s.timezone());
        }
        // an install that already holds a CLI import keeps the export's site URL, not the host the admin used
        if (settings.get("home_url").isEmpty()) {
            settings.put("home_url", home);
        }
        // emailed and printed links use this, never a request host
        if (settings.get("admin_url").isEmpty()) {
            settings.put("admin_url", home);
        }
        SiteReset.SETUP_DEFAULTS.forEach((k, v) -> {
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
                users.setProfile(u.getId(), s.displayName(), s.email());
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
        if (!UserFields.validLogin(s.login())) {
            return "login";
        }
        if (!UserFields.validEmail(s.email())) {
            return "email";
        }
        if (!UserFields.validDisplayName(s.displayName())) {
            return "displayName";
        }
        try {
            Passwords.validate(s.password());
        } catch (IllegalArgumentException e) {
            return "password";
        }
        if (s.timezone() != null) {
            try {
                ZoneId.of(s.timezone());
            } catch (DateTimeException e) {
                return "timezone";
            }
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
