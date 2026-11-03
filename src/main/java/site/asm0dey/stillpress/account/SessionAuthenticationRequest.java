package site.asm0dey.stillpress.account;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/**
 * The raw {@code sp_session} cookie value, handed to {@link SessionIdentityProvider}.
 */
public final class SessionAuthenticationRequest extends BaseAuthenticationRequest {
    private final String token;

    public SessionAuthenticationRequest(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
