package site.asm0dey.stillpress.account;

import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import site.asm0dey.stillpress.db.tables.pojos.Passkey;
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
                    .passkeys(u.id())
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
            return record(users.findById(p.userId()).orElseThrow().login(), p);
        });
    }

    private static WebAuthnCredentialRecord record(String login, Passkey p) {
        return WebAuthnCredentialRecord.fromRequiredPersistedData(
                new RequiredPersistedData(
                        login,
                        p.credentialId(),
                        UUID.fromString(p.aaguid()),
                        Base64.getDecoder().decode(p.publicKey()),
                        p.algorithm(),
                        p.signCount()
                )
        );
    }
}
