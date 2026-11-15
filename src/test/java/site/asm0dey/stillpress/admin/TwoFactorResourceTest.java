package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static site.asm0dey.stillpress.TestIdentities.user;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.Totp;
import site.asm0dey.stillpress.account.TwoFactorPolicy;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.db.tables.pojos.Passkey;
import site.asm0dey.stillpress.db.tables.pojos.TotpFactor;
import site.asm0dey.stillpress.store.TwoFactorRepository;

@ExtendWith(MockitoExtension.class)
class TwoFactorResourceTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
    @Mock
    Totp totp;
    @Mock
    TwoFactorRepository factors;
    @Mock
    WebAuthnSecurity webAuthn;
    @Mock
    TwoFactorPolicy policy;
    @Mock
    RoutingContext ctx;
    TwoFactorResource resource;

    @BeforeEach
    void setUp() {
        resource = new TwoFactorResource(user(7, "admin", Role.ADMINISTRATOR), totp, factors, webAuthn, policy);
    }

    private static TotpFactor totpRow(boolean confirmed) {
        return new TotpFactor(7L, "cipher", confirmed, null, AT);
    }

    private static Passkey passkey(String id, LocalDateTime lastUsed) {
        return new Passkey(id, 7L, "laptop", "pk", -7L, "aaguid", 0L, AT, lastUsed);
    }

    private static ItemService.Refused refusal(Runnable call) {
        return (ItemService.Refused) assertThatThrownBy(call::run).isInstanceOf(ItemService.Refused.class).actual();
    }

    private static WebAuthnCredentialRecord.RequiredPersistedData data() {
        return new WebAuthnCredentialRecord.RequiredPersistedData(
                "admin",
                "cred-1",
                UUID.randomUUID(),
                new byte[] {1},
                -7,
                0
        );
    }

    @Test
    void listShowsAConfirmedAuthenticatorThePasskeysAndWhetherTwoFactorIsRequired() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(true)));
        when(factors.passkeys(7)).thenReturn(List.of(passkey("a", null), passkey("b", AT.plusDays(1))));
        when(policy.required()).thenReturn(true);
        assertThat(resource.list())
            .isEqualTo(
                    new TwoFactorResource.Factors(
                            true,
                            List.of(
                                    new TwoFactorResource.PasskeyView("a", "laptop", "2026-01-02T03:04:05Z", null),
                                    new TwoFactorResource.PasskeyView(
                                            "b",
                                            "laptop",
                                            "2026-01-02T03:04:05Z",
                                            "2026-01-03T03:04:05Z"
                                    )
                            ),
                            true
                    )
            );
    }

    @Test
    void anUnconfirmedAuthenticatorIsNoFactor() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(false)));
        assertThat(resource.list()).isEqualTo(new TwoFactorResource.Factors(false, List.of(), false));
    }

    @Test
    void enrollStartsTheAuthenticatorForTheSignedInLogin() {
        var enrollment = new Totp.Enrollment("SECRET", "otpauth://totp/x", "<svg/>");
        when(factors.totp(7)).thenReturn(Optional.empty());
        when(totp.start(7, "admin")).thenReturn(enrollment);
        assertThat(resource.enroll()).isEqualTo(enrollment);
    }

    @Test
    void enrollOverAConfirmedAuthenticatorIs409TotpExistsAndStartsNothing() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(true)));
        var e = refusal(resource::enroll);
        assertThat(e.status).isEqualTo(409);
        assertThat(e.body()).isEqualTo(Map.of("error", "totp_exists"));
        verifyNoInteractions(totp);
    }

    @Test
    void anUnconfirmedAuthenticatorCanBeEnrolledAgain() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(false)));
        resource.enroll();
        verify(totp).start(7, "admin");
    }

    @Test
    void confirmWithTheRightCodeIs204() {
        when(totp.confirm(7, "123456")).thenReturn(true);
        try (var r = resource.confirm(new TwoFactorResource.Code("123456"))) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
    }

    @Test
    void aWrongOrMissingCodeIs400InvalidCodeOnCode() {
        when(totp.confirm(7, "000000x")).thenReturn(false);
        for (Runnable call :
                List.<Runnable>of(() -> resource.confirm(new TwoFactorResource.Code("000000x")), () -> resource.confirm(
                null
        ))) {
            var e = refusal(call);
            assertThat(e.status).isEqualTo(400);
            assertThat(e.body()).isEqualTo(Map.of("error", "invalid_code", "field", "code"));
        }
    }

    @Test
    void removeWithoutAnAuthenticatorIs404() {
        when(factors.totp(7)).thenReturn(Optional.empty());
        var e = refusal(resource::remove);
        assertThat(e.status).isEqualTo(404);
        assertThat(e.body()).isEqualTo(Map.of("error", "not_found"));
    }

    @Test
    void removingAConfirmedAuthenticatorIs204WhenTwoFactorIsNotRequired() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(true)));
        when(factors.deleteTotp(7, true)).thenReturn(1);
        try (var r = resource.remove()) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
    }

    @Test
    void removingTheLastFactorWhileRequiredIs409LastFactor() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(true)));
        when(factors.deleteTotp(7, true)).thenReturn(1);
        when(policy.required()).thenReturn(true);
        when(factors.hasFactor(7)).thenReturn(false);
        var e = refusal(resource::remove);
        assertThat(e.status).isEqualTo(409);
        assertThat(e.body()).isEqualTo(Map.of("error", "last_factor"));
    }

    @Test
    void removingAConfirmedAuthenticatorWhileRequiredIsFineWhenAnotherFactorRemains() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(true)));
        when(factors.deleteTotp(7, true)).thenReturn(1);
        when(policy.required()).thenReturn(true);
        when(factors.hasFactor(7)).thenReturn(true);
        try (var r = resource.remove()) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
    }

    @Test
    void cancellingAnUnconfirmedSetupIsNeverTheLastFactor() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(false)));
        when(factors.deleteTotp(7, false)).thenReturn(1);
        try (var r = resource.remove()) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(policy, never()).required();
    }

    @Test
    void aConfirmLandingBetweenReadAndDeleteIsReadAgainRatherThanRemovedUnchecked() {
        when(factors.totp(7)).thenReturn(Optional.of(totpRow(false)), Optional.of(totpRow(true)));
        when(factors.deleteTotp(7, false)).thenReturn(0);
        when(factors.deleteTotp(7, true)).thenReturn(1);
        try (var r = resource.remove()) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
        verify(factors).deleteTotp(7, true);
    }

    @Test
    void addPasskeyRegistersStoresAndAnswers201WithTheView() {
        var credential = new JsonObject().put("id", "cred-1");
        var record = mock(WebAuthnCredentialRecord.class);
        var data = data();
        when(record.getRequiredPersistedData()).thenReturn(data);
        when(webAuthn.register("admin", credential, ctx)).thenReturn(Uni.createFrom().item(record));
        when(factors.addPasskey(7, "laptop", data)).thenReturn(true);
        when(factors.passkey("cred-1")).thenReturn(Optional.of(passkey("cred-1", null)));
        try (var r = resource.addPasskey(new TwoFactorResource.NewPasskey("  laptop ", credential), ctx)) {
            assertThat(r.getStatus()).isEqualTo(201);
            assertThat(r.getEntity())
                .isEqualTo(new TwoFactorResource.PasskeyView("cred-1", "laptop", "2026-01-02T03:04:05Z", null));
        }
    }

    @Test
    void aBlankMissingOrOverlongPasskeyNameIs400OnNameAndRegistersNothing() {
        var credential = new JsonObject();
        for (var body :
                new TwoFactorResource.NewPasskey[] {
                null,
                new TwoFactorResource.NewPasskey(null, credential),
                new TwoFactorResource.NewPasskey("  ", credential),
                new TwoFactorResource.NewPasskey("n".repeat(101), credential)
        }) {
            var e = refusal(() -> resource.addPasskey(body, ctx));
            assertThat(e.status).isEqualTo(400);
            assertThat(e.body()).isEqualTo(Map.of("error", "invalid", "field", "name"));
        }
        verifyNoInteractions(webAuthn, factors);
    }

    @Test
    void aHundredCharacterNameIsAccepted() {
        var credential = new JsonObject();
        var record = mock(WebAuthnCredentialRecord.class);
        var data = data();
        var name = "n".repeat(100);
        when(record.getRequiredPersistedData()).thenReturn(data);
        when(webAuthn.register("admin", credential, ctx)).thenReturn(Uni.createFrom().item(record));
        when(factors.addPasskey(7, name, data)).thenReturn(true);
        when(factors.passkey("cred-1")).thenReturn(Optional.of(passkey("cred-1", null)));
        try (var r = resource.addPasskey(new TwoFactorResource.NewPasskey(name, credential), ctx)) {
            assertThat(r.getStatus()).isEqualTo(201);
        }
    }

    @Test
    void aRefusedRegistrationIs400InvalidPasskeyAndStoresNothing() {
        var credential = new JsonObject();
        when(webAuthn.register("admin", credential, ctx))
            .thenReturn(Uni.createFrom().failure(new IllegalStateException("bad")));
        var e = refusal(() -> resource.addPasskey(new TwoFactorResource.NewPasskey("laptop", credential), ctx));
        assertThat(e.status).isEqualTo(400);
        assertThat(e.body()).isEqualTo(Map.of("error", "invalid_passkey"));
        verifyNoInteractions(factors);
    }

    @Test
    void aReplayedRegistrationTheRepositoryDoesNotStoreIs400InvalidPasskey() {
        var credential = new JsonObject();
        var record = mock(WebAuthnCredentialRecord.class);
        var data = data();
        when(record.getRequiredPersistedData()).thenReturn(data);
        when(webAuthn.register("admin", credential, ctx)).thenReturn(Uni.createFrom().item(record));
        when(factors.addPasskey(any(Long.class), any(), any())).thenReturn(false);
        var e = refusal(() -> resource.addPasskey(new TwoFactorResource.NewPasskey("laptop", credential), ctx));
        assertThat(e.status).isEqualTo(400);
        assertThat(e.body()).isEqualTo(Map.of("error", "invalid_passkey"));
    }

    @Test
    void removePasskeyOfAnUnknownIdIs404() {
        when(factors.deletePasskey(7, "nope")).thenReturn(0);
        var e = refusal(() -> resource.removePasskey("nope"));
        assertThat(e.status).isEqualTo(404);
    }

    @Test
    void removePasskeyIs204AndKeepsOneFactorWhenRequired() {
        when(factors.deletePasskey(7, "a")).thenReturn(1);
        when(policy.required()).thenReturn(true);
        when(factors.hasFactor(7)).thenReturn(true);
        try (var r = resource.removePasskey("a")) {
            assertThat(r.getStatus()).isEqualTo(204);
        }
    }

    @Test
    void removingTheLastPasskeyWhileRequiredIs409LastFactor() {
        when(factors.deletePasskey(7, "a")).thenReturn(1);
        when(policy.required()).thenReturn(true);
        when(factors.hasFactor(7)).thenReturn(false);
        var e = refusal(() -> resource.removePasskey("a"));
        assertThat(e.status).isEqualTo(409);
        assertThat(e.body()).isEqualTo(Map.of("error", "last_factor"));
    }

    @Test
    void aRefusalIsItsStatusAndBody() {
        try (var r = resource.refused(new ItemService.Refused(400, "invalid_code", "code"))) {
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(r.getEntity()).isEqualTo(Map.of("error", "invalid_code", "field", "code"));
        }
    }
}
