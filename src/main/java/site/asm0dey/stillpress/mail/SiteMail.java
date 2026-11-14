package site.asm0dey.stillpress.mail;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.i18n.Localized;
import io.quarkus.qute.i18n.MessageBundles;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.LoginOption;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.account.Secrets;
import site.asm0dey.stillpress.store.SettingRepository;

/**
 * The site's outgoing mail, through the SMTP server in the {@code smtp_*} settings. Without {@code smtp_host} no client
 * exists, so nothing is ever sent.
 */
@ApplicationScoped
public class SiteMail {
    public static final String LAST_ERROR = "mail_last_error";
    public static final String PASSWORD = "smtp_password";
    private static final Logger LOG = Logger.getLogger(SiteMail.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    @Inject
    Vertx vertx;
    @Inject
    SettingRepository settings;
    @Inject
    Secrets secrets;
    @Inject
    Event<Mail> afterCommit;

    private record Client(MailClient client, String from) {}

    // built on first use after a reload, so it always reflects committed settings
    private Client client;
    private boolean loaded;

    /**
     * The texts in {@code locale} ({@code en} or {@code ru}).
     */
    public static MailTexts texts(String locale) {
        return MessageBundles.get(MailTexts.class, Localized.Literal.of(locale));
    }

    public boolean configured() {
        return settings.get("smtp_host").isPresent();
    }

    /**
     * Sends now, waiting up to 30 s.
     *
     * @throws MailFailedException when SMTP is not set up or the server does not take the mail
     */
    public void send(Mail m) {
        var c = client().orElseThrow(() -> new MailFailedException("SMTP is not configured"));
        var message = new MailMessage().setFrom(c.from()).setTo(m.to()).setSubject(m.subject()).setText(m.body());
        try {
            c.client().sendMail(message).toCompletionStage().toCompletableFuture().get(
                    TIMEOUT.toSeconds(),
                    TimeUnit.SECONDS
            );
        } catch (ExecutionException e) {
            var cause = e.getCause();
            throw new MailFailedException(Objects.requireNonNullElse(
                    cause.getMessage(),
                    cause.getClass().getSimpleName()
            ));
        } catch (TimeoutException e) {
            throw new MailFailedException("No answer from the SMTP server in " + TIMEOUT.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailFailedException("Interrupted while sending");
        }
    }

    /**
     * Sends once the current transaction commits, and not at all if it rolls back; outside a transaction, now. A
     * failure never reaches the caller: it is logged and kept in {@value #LAST_ERROR} for the admin.
     */
    public void sendAfterCommit(Mail m) {
        // CDI calls a transactional observer at once when no transaction is active
        afterCommit.fire(m);
    }

    void deliver(@Observes(during = TransactionPhase.AFTER_SUCCESS) Mail m) {
        try {
            send(m);
        } catch (RuntimeException e) {
            LOG.warnf("Mail \"%s\" was not sent: %s", m.subject(), e.getMessage());
            QuarkusTransaction
                .requiringNew()
                .run(() -> settings.put(LAST_ERROR, Instant.now() + " " + e.getMessage()));
        }
    }

    /**
     * Drops the client; the next send builds one from the settings as they are then.
     */
    public synchronized void reload() {
        if (client != null) {
            client.client().close();
        }
        client = null;
        loaded = false;
    }

    private synchronized Optional<Client> client() {
        if (!loaded) {
            // its own transaction: the caller may be in a transaction's after-commit phase, which can no longer read
            client = QuarkusTransaction.requiringNew().call(this::build);
            loaded = true;
        }
        return Optional.ofNullable(client);
    }

    private Client build() {
        var host = settings.get("smtp_host");
        if (host.isEmpty()) {
            return null;
        }
        var config = new MailConfig()
            .setHostname(host.get())
            .setPort(settings.get("smtp_port").map(Integer::valueOf).orElse(587))
            .setConnectTimeout((int) TIMEOUT.toMillis());
        switch (settings.get("smtp_security").orElse("starttls")) {
            case "tls" -> config.setSsl(true).setStarttls(StartTLSOptions.DISABLED);
            case "none" -> config.setStarttls(StartTLSOptions.DISABLED);
            default -> config.setStarttls(StartTLSOptions.REQUIRED);
        }
        var username = settings.get("smtp_username");
        if (username.isPresent()) {
            config.setLogin(LoginOption.REQUIRED).setUsername(username.get()).setPassword(secrets
                .get(PASSWORD)
                .orElse("")
            );
        } else {
            config.setLogin(LoginOption.DISABLED);
        }
        return new Client(MailClient.create(vertx, config), settings.get("smtp_from").orElse(""));
    }
}
