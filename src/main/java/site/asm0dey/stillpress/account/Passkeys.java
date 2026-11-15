package site.asm0dey.stillpress.account;

import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.db.tables.records.PasskeyRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.TwoFactorRepository;

/**
 * Read-only view of the passkey table for the WebAuthn extension. Stores happen in {@code TwoFactorResource}, so the
 * default no-op {@code store} and {@code update} stay.
 */
@ApplicationScoped
public class Passkeys implements WebAuthnUserProvider {
    @Inject
    TwoFactorRepository factors;
    @Inject
    AuthorRepository users;

    @Override
    public Uni<List<WebAuthnCredentialRecord>> findByUsername(String login) {
        return blocking(() -> users
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
        return blocking(() -> {
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

    private static <T> Uni<T> blocking(Supplier<T> query) {
        return Uni.createFrom().item(query).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
