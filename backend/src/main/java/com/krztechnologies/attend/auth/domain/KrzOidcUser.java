package com.krztechnologies.attend.auth.domain;

import com.krztechnologies.attend.common.security.AttendPrincipal;
import java.io.Serial;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * The signed-in user: the validated Entra ID token plus the KRZ Attend employee it belongs to. The principal name is
 * the Entra object ID ({@code oid}), which also indexes the user's sessions for revocation. Permissions are NOT stored
 * here: they are read on every request (cached 30 s), so role changes apply at once.
 */
public class KrzOidcUser extends DefaultOidcUser implements AttendPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID employeeId;
    private final String displayName;

    public KrzOidcUser(OidcIdToken idToken, UUID employeeId, String displayName) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, "oid");
        this.employeeId = employeeId;
        this.displayName = displayName;
    }

    @Override
    public UUID employeeId() {
        return employeeId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KrzOidcUser user && super.equals(user) && employeeId.equals(user.employeeId);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + employeeId.hashCode();
    }
}
