package com.krztechnologies.attend.auth.service;

import com.krztechnologies.attend.auth.domain.KrzOidcUser;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.graph.GraphException;
import com.krztechnologies.attend.directory.service.DirectoryLookupService;
import com.krztechnologies.attend.directory.service.DirectoryScope;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Checks every Microsoft sign-in beyond the standard ID-token validation (signature, iss, aud, exp, nbf, nonce, done
 * by Spring Security): {@code tid} is the configured tenant, {@code oid} is present, the account is a member (not a
 * guest) in the allowed domain and in sync scope. The employee is matched by (tid, oid) — never by e-mail — and
 * created just in time if the first sync has not run yet. Failures redirect to {@code /signin?error=<code>}.
 */
@Service
public class EntraOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** Error codes the frontend maps to the prototype's outcome screens. */
    public static final String WRONG_TENANT = "wrong_tenant";

    public static final String GUEST = "guest";
    public static final String WRONG_DOMAIN = "wrong_domain";
    public static final String NOT_IN_SCOPE = "not_in_scope";
    public static final String GENERIC = "generic";

    private static final Logger LOG = LoggerFactory.getLogger(EntraOidcUserService.class);

    private final EntraProperties entra;
    private final EmployeeIdentityService identities;
    private final DirectoryLookupService lookup;
    private final SignInService signIn;

    public EntraOidcUserService(
            EntraProperties entra,
            EmployeeIdentityService identities,
            DirectoryLookupService lookup,
            SignInService signIn) {
        this.entra = entra;
        this.identities = identities;
        this.lookup = lookup;
        this.signIn = signIn;
    }

    /**
     * The ID token was already validated by Spring Security and carries everything needed, so the userinfo endpoint is
     * never called.
     */
    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcIdToken token = request.getIdToken();
        String upn = firstNonBlank(token.getPreferredUsername(), token.getClaimAsString("upn"));
        String tid = token.getClaimAsString("tid");
        if (tid == null || !tid.equalsIgnoreCase(entra.tenantId().toString())) {
            throw refuse(upn, WRONG_TENANT);
        }
        Optional<UUID> objectId = uuid(token.getClaimAsString("oid"));
        if (objectId.isEmpty() || upn == null) {
            throw refuse(upn, GENERIC);
        }
        String lowerUpn = upn.toLowerCase(Locale.ROOT);
        if (lowerUpn.contains("#ext#")) {
            throw refuse(upn, GUEST);
        }
        if (!lowerUpn.endsWith("@" + entra.allowedDomain())) {
            throw refuse(upn, WRONG_DOMAIN);
        }
        UUID tenantId = entra.tenantId();
        EmployeeIdentityService.Identity identity = identities
                .findByEntraIdentity(tenantId, objectId.get())
                .orElseGet(() -> justInTime(tenantId, objectId.get(), token, upn));
        signIn.completeSignIn(identity);
        return new KrzOidcUser(token, identity.id(), identity.displayName());
    }

    private EmployeeIdentityService.Identity justInTime(UUID tenantId, UUID objectId, OidcIdToken token, String upn) {
        DirectoryLookupService.Lookup result;
        try {
            result = lookup.lookup(objectId);
        } catch (GraphException e) {
            if (entra.syncScope() == EntraProperties.SyncScope.GROUPS) {
                LOG.warn("JIT lookup failed ({}); cannot check group scope", e.code());
                throw refuse(upn, GENERIC);
            }
            // Graph unavailable: the validated ID token of a member in the allowed domain is enough for an identity;
            // the next directory sync completes it.
            LOG.warn("JIT lookup failed ({}); creating the identity from the ID token", e.code());
            return identities.createFromSignIn(
                    tenantId,
                    new EmployeeIdentityService.DirectoryEntry(
                            objectId,
                            firstNonBlank(token.getClaimAsString("name"), upn),
                            token.getClaimAsString("given_name"),
                            token.getClaimAsString("family_name"),
                            upn,
                            token.getClaimAsString("email"),
                            true));
        }
        return switch (result) {
            case DirectoryLookupService.Lookup.Found found -> identities.createFromSignIn(tenantId, found.entry());
            case DirectoryLookupService.Lookup.OutOfScope out -> throw refuse(upn, codeFor(out.reason()));
            case DirectoryLookupService.Lookup.NotFound notFound -> throw refuse(upn, NOT_IN_SCOPE);
        };
    }

    private OAuth2AuthenticationException refuse(String upn, String code) {
        signIn.recordRefusal(upn, code);
        LOG.info("Sign-in refused: {}", code);
        return new OAuth2AuthenticationException(new OAuth2Error(code), code);
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
    }

    private static String codeFor(DirectoryScope.Exclusion exclusion) {
        return switch (exclusion) {
            case GUEST -> GUEST;
            case WRONG_DOMAIN -> WRONG_DOMAIN;
            case EXCLUDED, NOT_IN_GROUP -> NOT_IN_SCOPE;
        };
    }
}
