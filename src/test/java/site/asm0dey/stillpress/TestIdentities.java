package site.asm0dey.stillpress;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SessionIdentityProvider;

/**
 * Real identities shaped like the ones {@link SessionIdentityProvider} builds, for calling resources directly.
 */
public final class TestIdentities {
    private TestIdentities() {
    }

    public static SecurityIdentity user(long id, String login, Role role) {
        return QuarkusSecurityIdentity
            .builder()
            .setPrincipal(new QuarkusPrincipal(login))
            .addRole(role.id())
            .addAttribute(SessionIdentityProvider.USER_ID, id)
            .addAttribute(SessionIdentityProvider.ENROLL_REQUIRED, false)
            .build();
    }

    public static SecurityIdentity anonymous() {
        return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
    }
}
