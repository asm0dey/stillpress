package site.asm0dey.stillpress.account;

import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.db.tables.records.PasskeyRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * Read-only view of the passkey table for the WebAuthn extension. Stores happen in {@code TwoFactorResource}, so the
 * default no-op {@code store} and {@code update} stay. {@code @Blocking} makes the extension run a lookup inline when
 * called on a worker thread and hand it to a worker only from the event loop, so one request never holds two workers.
 */
@ApplicationScoped
@Blocking
public class Passkeys implements WebAuthnUserProvider {
    private final TwoFactorRepository factors;
    private final AuthorRepository users;

    Passkeys(TwoFactorRepository factors, AuthorRepository users) {
        this.factors = factors;
        this.users = users;
    }

    @Override
    public Uni<List<WebAuthnCredentialRecord>> findByUsername(String login) {
        return Uni
            .createFrom()
            .item(() -> users
                .findByLogin(login)
                .map(u -> factors
                    .passkeys(u.getId())
                    .stream()
                    .map(p -> record(login, p))
                    .toList())
                .orElse(List.of()));
    }

    @Override
    public Uni<WebAuthnCredentialRecord> findByCredentialId(String credentialId) {
        return Uni.createFrom().item(() -> {
            var p = factors
                .passkey(credentialId)
                .orElseThrow(() -> new NoSuchElementException("unknown passkey"));
            return record(users.findById(p.getUserId()).orElseThrow().getLogin(), p);
        });
    }

    private static WebAuthnCredentialRecord record(String login, PasskeyRecord p) {
        return WebAuthnCredentialRecord.fromRequiredPersistedData(
                new RequiredPersistedData(
                        login,
                        p.getCredentialId(),
                        UUID.fromString(p.getAaguid()),
                        Base64.getDecoder().decode(p.getPublicKey()),
                        p.getAlgorithm(),
                        p.getSignCount()
                )
        );
    }
}
