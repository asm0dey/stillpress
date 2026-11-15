package site.asm0dey.stillpress.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.account.UserFields;
import site.asm0dey.stillpress.mail.Mail;
import site.asm0dey.stillpress.mail.MailFailedException;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The SMTP server the site mails through. The password is kept encrypted and never sent back; an empty host turns
 * mail off.
 */
@Path("/admin/api/mail")
@RolesAllowed("administrator")
public class MailResource {
    private static final Set<String> SECURITY = Set.of("none", "starttls", "tls");
    private static final List<String> KEYS =
            List.of("smtp_host", "smtp_port", "smtp_security", "smtp_username", "smtp_from");
    private final SettingRepository settings;
    private final Secrets secrets;
    private final SiteMail mail;
    private final AuthorRepository users;
    private final SecurityIdentity identity;

    MailResource(
            SettingRepository settings,
            Secrets secrets,
            SiteMail mail,
            AuthorRepository users,
            SecurityIdentity identity
    ) {
        this.settings = settings;
        this.secrets = secrets;
        this.mail = mail;
        this.users = users;
        this.identity = identity;
    }

    public record Settings(
            String host,
            int port,
            String security,
            String username,
            String from,
            boolean passwordSet,
            String lastError
    ) {}

    /**
     * {@code password}: {@code null} keeps the stored one, {@code ""} deletes it.
     */
    public record Update(String host, Integer port, String security, String username, String from, String password) {}

    @GET
    public Settings get() {
        return new Settings(
                settings.get("smtp_host").orElse(""),
                settings.get("smtp_port").map(Integer::valueOf).orElse(587),
                settings.get("smtp_security").orElse("starttls"),
                settings.get("smtp_username").orElse(""),
                settings.get("smtp_from").orElse(""),
                secrets.get(SiteMail.PASSWORD).isPresent(),
                settings.get(SiteMail.LAST_ERROR).orElse(null)
        );
    }

    @PUT
    public Response put(Update u) {
        var field = invalidField(u);
        if (field != null) {
            return Response.status(400).entity(Map.of("error", "invalid", "field", field)).build();
        }
        // committed before the reload, so the rebuilt client reads the new settings
        QuarkusTransaction.requiringNew().run(() -> save(u));
        mail.reload();
        return Response.ok(get()).build();
    }

    private void save(Update u) {
        var host = u.host().strip();
        if (host.isEmpty()) {
            KEYS.forEach(settings::delete);
            secrets.delete(SiteMail.PASSWORD);
            return;
        }
        settings.put("smtp_host", host);
        settings.put("smtp_port", String.valueOf(u.port()));
        settings.put("smtp_security", u.security());
        var username = Objects.requireNonNullElse(UserFields.trim(u.username()), "");
        if (username.isEmpty()) {
            settings.delete("smtp_username");
        } else {
            settings.put("smtp_username", username);
        }
        settings.put("smtp_from", UserFields.trim(u.from()));
        if ("".equals(u.password())) {
            secrets.delete(SiteMail.PASSWORD);
        } else if (u.password() != null) {
            secrets.put(SiteMail.PASSWORD, u.password());
        }
    }

    /**
     * The first bad field in form order, or {@code null}; nothing else is checked when the host is cleared.
     */
    private static String invalidField(Update u) {
        if (u == null || u.host() == null) {
            return "host";
        }
        if (u.host().isBlank()) {
            return null;
        }
        if (u.port() == null || u.port() < 1 || u.port() > 65535) {
            return "port";
        }
        if (!SECURITY.contains(u.security())) {
            return "security";
        }
        if (!UserFields.validEmail(UserFields.trim(u.from()))) {
            return "from";
        }
        return null;
    }

    /**
     * Sends the test mail to the signed-in administrator, in their admin language.
     */
    @POST
    @Path("test")
    public Response test() {
        long id = identity.getAttribute(SessionIdentityProvider.USER_ID);
        var me = users.findById(id).orElseThrow();
        if (me.email() == null || me.email().isBlank()) {
            return Response.status(400).entity(Map.of("error", "no_email")).build();
        }
        if (!mail.configured()) {
            return Response.status(409).entity(Map.of("error", "mail_not_configured")).build();
        }
        var site = settings.get("title").orElse("Stillpress");
        var texts = SiteMail.texts(AuthResource.adminLocale(me.locale(), settings.get("language")));
        try {
            mail.send(new Mail(me.email(), texts.testSubject(site), texts.testBody(site)));
        } catch (MailFailedException e) {
            return Response.status(502).entity(Map.of("error", "mail_failed", "message", e.getMessage())).build();
        }
        settings.delete(SiteMail.LAST_ERROR);
        return Response.noContent().build();
    }
}
