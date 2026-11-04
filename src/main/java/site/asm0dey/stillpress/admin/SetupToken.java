package site.asm0dey.stillpress.admin;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import module java.base;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.store.AuthorRepository;

/**
 * The one-time secret that proves the person running the wizard can read the server log. Exists only while the HTTP
 * server is up and no administrator with a password exists.
 */
@ApplicationScoped
public class SetupToken {
    private static final Logger LOG = Logger.getLogger(SetupToken.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    @Inject
    AuthorRepository users;
    // Main turns this off for CLI commands, which must not print a token nobody can use
    @ConfigProperty(name = "quarkus.http.host-enabled", defaultValue = "true")
    boolean httpEnabled;
    private volatile String token;

    void onStart(@Observes StartupEvent e) {
        if (httpEnabled && !users.anyAdministratorWithPassword()) {
            regenerate();
        }
    }

    /**
     * A fresh token, logged for the person running the wizard.
     */
    void regenerate() {
        var b = new byte[24];
        RANDOM.nextBytes(b);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        LOG.infof("Setup token: %s — open /admin to create the site", token);
    }

    public Optional<String> current() {
        return Optional.ofNullable(token);
    }

    public boolean matches(String candidate) {
        var t = token;
        return t != null
                && candidate != null
                && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    public void discard() {
        token = null;
    }
}
