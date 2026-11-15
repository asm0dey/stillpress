package site.asm0dey.stillpress.admin;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.logging.Logger;
import site.asm0dey.stillpress.account.Captcha;
import site.asm0dey.stillpress.account.CaptchaFailedException;
import site.asm0dey.stillpress.account.LoginLimiter;
import site.asm0dey.stillpress.account.PasskeyChallenges;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SessionAuthMechanism;
import site.asm0dey.stillpress.account.SessionIdentityProvider;
import site.asm0dey.stillpress.account.SetPasswordLink;
import site.asm0dey.stillpress.account.Totp;
import site.asm0dey.stillpress.account.TwoFactorPolicy;
import site.asm0dey.stillpress.mail.Mail;
import site.asm0dey.stillpress.mail.SiteMail;
import site.asm0dey.stillpress.store.Author;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * Password login, logout, the current session, mailed password resets, and setting a password from a single-use token.
 */
@Path("/admin/api")
public class AuthResource {
    // an unknown login is verified against this, so it costs the same as a wrong password
    private static final String DUMMY_HASH = Passwords.hash("no such user, same Argon2id cost");
    private static final Logger LOG = Logger.getLogger(AuthResource.class);
    private static final int MAX_LOGIN = 100;
    private static final Duration RESET_INTERVAL = Duration.ofMinutes(5);
    private static final Map<String, String> INVALID_CREDENTIALS = Map.of("error", "invalid_credentials");
    private static final Map<String, String> SECOND_STEP_EXPIRED = Map.of("error", "second_step_expired");
    private final Vertx vertx;
    private final LoginLimiter limiter;
    private final Captcha captcha;
    private final AuthorRepository users;
    private final SessionRepository sessions;
    private final TokenRepository tokens;
    private final SettingRepository settings;
    private final SecurityIdentity identity;
    private final SiteMail mail;
    private final SetPasswordLink links;
    private final TwoFactorRepository factors;
    private final Totp totp;
    private final WebAuthnSecurity webAuthn;
    private final PasskeyChallenges challenges;
    private final TwoFactorPolicy policy;
    @Context
    HttpServerRequest request;

    /**
     * {@code captcha} is the provider's token and {@code website} the honeypot, on every public form.
     */
    public record Credentials(String login, String password, String captcha, String website) {}

    public record NewPassword(String token, String password, String captcha, String website) {}

    /**
     * {@code enrollRequired}: 2FA is mandatory and this user has no factor yet, so only enrollment is open.
     */
    public record SessionUser(
            long id,
            String login,
            String displayName,
            String email,
            String role,
            String locale,
            boolean enrollRequired
    ) {}

    public record NewLocale(String locale) {}

    /**
     * The password was right; these factors can finish the sign-in.
     */
    public record SecondStep(boolean totp, boolean passkey) {}

    public record ResetRequest(String login, String captcha, String website) {}

    /**
     * What the login screen offers: {@code mail} says whether "Forgot password?" can mail a link,
     * {@code captcha} which widget the forms show.
     */
    public record LoginOptions(boolean mail, String captcha, String turnstileSiteKey) {}

    private static final Set<String> LOCALES = Set.of("en", "ru");

    AuthResource(
            Vertx vertx,
            LoginLimiter limiter,
            Captcha captcha,
            AuthorRepository users,
            SessionRepository sessions,
            TokenRepository tokens,
            SettingRepository settings,
            SecurityIdentity identity,
            SiteMail mail,
            SetPasswordLink links,
            TwoFactorRepository factors,
            Totp totp,
            WebAuthnSecurity webAuthn,
            PasskeyChallenges challenges,
            TwoFactorPolicy policy
    ) {
        this.vertx = vertx;
        this.limiter = limiter;
        this.captcha = captcha;
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
        this.settings = settings;
        this.identity = identity;
        this.mail = mail;
        this.links = links;
        this.factors = factors;
        this.totp = totp;
        this.webAuthn = webAuthn;
        this.challenges = challenges;
        this.policy = policy;
    }

    @POST
    @Path("login")
    public Response login(Credentials c) {
        if (c == null || c.login() == null || c.password() == null) {
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        // the proxy-forwarding layer has already replaced this with the client's address when a trusted proxy sent it
        var ip = request.remoteAddress().hostAddress();
        var wait = limiter.blockedIp(ip);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        // before any lookup, and never against the account: a bot without a token learns nothing and locks no one out
        verifyCaptcha(c.captcha(), c.website(), ip);
        // no real login or password is this long: refuse before it becomes a limiter key or Argon2 input, counting
        // it against the IP only so oversized spam still runs into the IP limit
        if (c.login().length() > MAX_LOGIN
                || c.password().codePointCount(0, c.password().length()) > Passwords.MAX_LENGTH) {
            limiter.ipFailure(ip);
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        var user = users.findByLoginOrEmail(c.login());
        // the account bucket follows the user, so login and email share it; unknown names get their own
        var account = user
            .map(u -> "user:" + u.id())
            .orElse("login:" + c.login());
        wait = limiter.blockedAccount(account);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        var hash = user.map(Author::passwordHash).orElse(null);
        // a user without a password (imported) also pays the full hash cost, and never succeeds
        var ok = Passwords.verify(c.password(), hash == null ? DUMMY_HASH : hash) && hash != null;
        if (!ok) {
            limiter.failure(account, ip);
            return Response.status(401).entity(INVALID_CREDENTIALS).build();
        }
        var id = user.get().id();
        var second =
                new SecondStep(factors.totp(id).filter(t -> t.confirmed()).isPresent(), !factors
            .passkeys(id)
            .isEmpty());
        if (second.totp() || second.passkey()) {
            // half-way: the account bucket is reset only once the second step succeeds
            var raw = tokens.create(id, TokenRepository.SECOND_FACTOR, TokenRepository.SECOND_FACTOR_TTL);
            return Response
                .ok(second)
                .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.secondStepCookie(raw, secure()).encode())
                .build();
        }
        limiter.success(account);
        return signedIn(id).build();
    }

    @POST
    @Path("login/totp")
    public Response loginTotp(TwoFactorResource.Code c, @CookieParam(SessionAuthMechanism.SECOND_STEP) String raw) {
        return secondStep(raw, "invalid_code", id -> c != null && totp.verify(id, c.code()));
    }

    @POST
    @Path("login/passkey-step")
    public Response loginPasskeyStep(
            JsonObject credential,
            @CookieParam(SessionAuthMechanism.SECOND_STEP) String raw,
            RoutingContext ctx
    ) {
        return secondStep(raw, "invalid_passkey", id -> {
            var login = users.findById(id).map(Author::login);
            return verifyPasskey(credential, ctx, r -> login.filter(r.getUsername()::equals).isPresent()).isPresent();
        });
    }

    /**
     * A discoverable passkey alone signs in: it is possession and user verification in one. No CAPTCHA: the
     * authenticator's signature is the proof of a human, and a bad one costs only the IP.
     */
    @POST
    @Path("login/passkey")
    public Response loginPasskey(JsonObject credential, RoutingContext ctx) {
        var ip = request.remoteAddress().hostAddress();
        var wait = limiter.blockedIp(ip);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        var user = verifyPasskey(credential, ctx, _ -> true).flatMap(r -> users.findByLogin(r.getUsername()));
        if (user.isEmpty()) {
            limiter.ipFailure(ip);
            return Response.status(401).entity(Map.of("error", "invalid_passkey")).build();
        }
        return signedIn(user.get().id()).build();
    }

    /**
     * Finishes the sign-in the {@code sp_2fa} token started: the token is spent and a session made only when
     * {@code check} passes; a failure counts against the account and leaves the token for a retry.
     */
    private Response secondStep(String raw, String invalid, LongPredicate check) {
        var ip = request.remoteAddress().hostAddress();
        var wait = limiter.blockedIp(ip);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        var id = tokens.peek(raw, TokenRepository.SECOND_FACTOR);
        if (id.isEmpty()) {
            return Response.status(401).entity(SECOND_STEP_EXPIRED).build();
        }
        var account = "user:" + id.get();
        wait = limiter.blockedAccount(account);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        if (!check.test(id.get())) {
            limiter.failure(account, ip);
            return Response.status(401).entity(Map.of("error", invalid)).build();
        }
        // the delete is the claim: of two concurrent finishes only one gets a session
        if (tokens.consume(raw, TokenRepository.SECOND_FACTOR).isEmpty()) {
            return Response.status(401).entity(SECOND_STEP_EXPIRED).build();
        }
        limiter.success(account);
        return signedIn(id.get())
            .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.clearedSecondStepCookie(secure()).encode())
            .build();
    }

    /**
     * The verified passkey record when the assertion is valid, its challenge unspent and {@code accept} agrees; the
     * passkey's counter and last use are then recorded.
     */
    private Optional<WebAuthnCredentialRecord> verifyPasskey(
            JsonObject credential,
            RoutingContext ctx,
            Predicate<WebAuthnCredentialRecord> accept
    ) {
        WebAuthnCredentialRecord passkey;
        String challenge;
        try {
            passkey = webAuthn.login(credential, ctx).await().atMost(Duration.ofSeconds(10));
            // verified above, so the challenge in the signed client data is the one the server issued
            var clientData =
                    Base64.getUrlDecoder().decode(credential.getJsonObject("response").getString("clientDataJSON"));
            challenge = new JsonObject(new String(clientData, StandardCharsets.UTF_8)).getString("challenge");
        } catch (RuntimeException e) {
            LOG.debugf(e, "passkey sign-in refused");
            return Optional.empty();
        }
        if (!accept.test(passkey) || !challenges.claim(challenge)) {
            return Optional.empty();
        }
        factors.usePasskey(passkey.getCredentialID(), passkey.getCounter());
        return Optional.of(passkey);
    }

    private Response.ResponseBuilder signedIn(long userId) {
        var raw = sessions.create(userId);
        return Response
            .noContent()
            .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.cookie(raw, secure()).encode());
    }

    @GET
    @Path("login-options")
    public LoginOptions loginOptions() {
        var provider = captcha.provider();
        var siteKey = provider.equals("turnstile") ? captcha.turnstileSiteKey().orElse(null) : null;
        return new LoginOptions(mail.configured(), provider, siteKey);
    }

    /**
     * Mails a one-hour set-password link to the account with this login or email. The answer is 204 whether or not
     * the account exists, has an email or gets a mail, so it tells nothing about accounts; at most one mail per
     * account per five minutes.
     */
    @POST
    @Path("password-reset")
    public Response passwordReset(ResetRequest r) {
        var ip = request.remoteAddress().hostAddress();
        var wait = limiter.blockedIp(ip);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        // every request counts, so the endpoint cannot be used to spray mail or probe accounts at volume
        limiter.ipFailure(ip);
        // after the IP check, so a blocked IP makes no siteverify call; already counted once above
        captcha.verify(r == null ? null : r.captcha(), r == null ? null : r.website(), ip);
        var login = r == null ? null : r.login();
        if (login != null && login.length() <= MAX_LOGIN) {
            // lookup, throttle and send leave the request path, so the answer takes the same time for every login
            vertx.executeBlocking(() -> {
                try {
                    QuarkusTransaction
                        .requiringNew()
                        .run(() -> mailReset(login));
                } catch (RuntimeException e) {
                    LOG.warnf(e, "Password reset failed: %s", e.getMessage());
                }
                return null;
            });
        }
        return Response.noContent().build();
    }

    private void mailReset(String login) {
        if (!mail.configured()) {
            return;
        }
        users
            .findByLoginOrEmail(login)
            .filter(u -> u.email() != null && !u.email().isBlank())
            .ifPresent(u -> links
                .issueUnlessIssuedSince(
                        u.id(),
                        TokenRepository.SET_PASSWORD,
                        SetPasswordLink.RESET_TTL,
                        Instant.now().minus(RESET_INTERVAL)
                )
                .ifPresent(link -> {
                    var site = settings.get("title").orElse("Stillpress");
                    var texts = SiteMail.texts(adminLocale(u.locale(), settings.get("language")));
                    var minutes = (int) SetPasswordLink.RESET_TTL.toMinutes();
                    mail.sendAfterCommit(
                            new Mail(
                                    u.email(),
                                    texts.resetSubject(site),
                                    texts.resetBody(UsersResource.displayName(u), site, link, minutes)
                            )
                    );
                }));
    }

    @POST
    @Path("logout")
    public Response logout(@CookieParam(SessionAuthMechanism.COOKIE) String raw) {
        sessions.delete(raw);
        return Response
            .noContent()
            .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.clearedCookie(secure()).encode())
            .build();
    }

    @GET
    @Path("session")
    @Authenticated
    public SessionUser session() {
        long id = identity.getAttribute(SessionIdentityProvider.USER_ID);
        var u = users.findById(id).orElseThrow();
        // after an upgrade the first administrator in fills the address links are built from
        if (Role.ADMINISTRATOR.id().equals(u.role())) {
            Origins
                .of(request)
                .ifPresent(o -> settings.compareAndSet("admin_url", null, o));
        }
        var locale = adminLocale(u.locale(), settings.get("language"));
        return new SessionUser(
                u.id(),
                u.login(),
                u.displayName(),
                u.email(),
                u.role(),
                locale,
                Boolean.TRUE.equals(identity.getAttribute(SessionIdentityProvider.ENROLL_REQUIRED))
        );
    }

    /**
     * The user's own choice; otherwise the site language decides between the two admin languages.
     */
    public static String adminLocale(String userLocale, Optional<String> siteLanguage) {
        return userLocale != null
                ? userLocale
                : siteLanguage.filter(l -> l.startsWith("ru")).map(l -> "ru").orElse("en");
    }

    /**
     * The admin language of the signed-in user; a preference, not site content, so it leaves the import open.
     */
    @PUT
    @Path("session/locale")
    @Authenticated
    public Response setLocale(NewLocale l) {
        if (l == null || l.locale() == null || !LOCALES.contains(l.locale())) {
            return Response.status(400).entity(Map.of("error", "invalid_locale")).build();
        }
        users.setLocale(identity.getAttribute(SessionIdentityProvider.USER_ID), l.locale());
        return Response.noContent().build();
    }

    @POST
    @Path("set-password")
    @Transactional
    public Response setPassword(NewPassword p) {
        var ip = request.remoteAddress().hostAddress();
        var wait = limiter.blockedIp(ip);
        if (wait.isPresent()) {
            return rateLimited(wait.get());
        }
        verifyCaptcha(p == null ? null : p.captcha(), p == null ? null : p.website(), ip);
        var password = p == null ? null : p.password();
        try {
            Passwords.validate(password);
        } catch (IllegalArgumentException e) {
            // checked before the token is spent, so the user can retry with a longer password
            return Response.status(400).entity(Map.of("error", "weak_password")).build();
        }
        // one transaction: the token is spent only if the password is set and every old session is revoked
        var purposes = Set.of(TokenRepository.INVITE, TokenRepository.SET_PASSWORD);
        var userId = tokens.consumeAny(p.token(), purposes);
        if (userId.isEmpty()) {
            return Response.status(410).entity(Map.of("error", "invalid_token")).build();
        }
        // hashing is the slow step: a bad token is refused before paying for it
        var hash = Passwords.hash(password);
        users.setPassword(userId.get(), hash);
        // the other link of the pair dies with the one used
        tokens.deleteAll(userId.get(), purposes);
        sessions.deleteAllFor(userId.get());
        // with 2FA mandatory a user without a factor goes straight to enrolling one; a reset never skips a factor
        if (policy.required() && !factors.hasFactor(userId.get())) {
            var raw = sessions.create(userId.get());
            return Response
                .ok(Map.of("enroll", true))
                .header(HttpHeaders.SET_COOKIE, SessionAuthMechanism.cookie(raw, secure()).encode())
                .build();
        }
        return Response.noContent().build();
    }

    /**
     * A CAPTCHA failure counts against the IP, so one client cannot drive unbounded siteverify calls.
     */
    private void verifyCaptcha(String token, String honeypot, String ip) {
        try {
            captcha.verify(token, honeypot, ip);
        } catch (CaptchaFailedException e) {
            limiter.ipFailure(ip);
            throw e;
        }
    }

    private static Response rateLimited(Duration wait) {
        // whole seconds, rounded up: retrying at the stated time must not be refused again
        var seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return Response
            .status(429)
            .header(HttpHeaders.RETRY_AFTER, seconds)
            .entity(Map.of("error", "rate_limited"))
            .build();
    }

    private boolean secure() {
        return "https".equals(request.scheme());
    }
}
