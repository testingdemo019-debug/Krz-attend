package com.krztechnologies.attend.auth.service;

import com.krztechnologies.attend.access.service.UserAccessService;
import com.krztechnologies.attend.auth.domain.AccessState;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of a sign-in, in one transaction: last sign-in time, the first-administrator rule
 * (BOOTSTRAP_ADMIN_EMAILS, only while no System Administrator exists) and the audit entry. Refused sign-ins are
 * audited too.
 */
@Service
public class SignInService {

    private final EmployeeIdentityService identities;
    private final UserAccessService access;
    private final AccessStateResolver states;
    private final AuditService audit;
    private final AppProperties app;

    public SignInService(
            EmployeeIdentityService identities,
            UserAccessService access,
            AccessStateResolver states,
            AuditService audit,
            AppProperties app) {
        this.identities = identities;
        this.access = access;
        this.states = states;
        this.audit = audit;
        this.app = app;
    }

    @Transactional
    public AccessState completeSignIn(EmployeeIdentityService.Identity identity) {
        identities.recordSignIn(identity.id());
        boolean listed = app.bootstrapAdminEmails().stream()
                .anyMatch(e -> e.toLowerCase(Locale.ROOT).equals(identity.upn().toLowerCase(Locale.ROOT)));
        if (listed && identity.accountEnabled()) {
            access.grantBootstrapAdministrator(identity.id(), identity.displayName());
        }
        AccessState state = states.resolve(identity.id());
        String action = switch (state) {
            case ACTIVE -> "Signed in";
            case ACCOUNT_DISABLED -> "Sign-in blocked — account disabled";
            default -> "Signed in — no access";
        };
        audit.record(
                AuditActor.employee(identity.id(), identity.displayName()),
                AuditCategory.SIGN_IN,
                action,
                identity.displayName(),
                null,
                null,
                Map.of("method", "Microsoft Entra ID"));
        return state;
    }

    /** A sign-in Microsoft accepted but KRZ Attend refused (wrong tenant, guest, other domain, out of scope). */
    @Transactional
    public void recordRefusal(String who, String reasonCode) {
        audit.record(
                new AuditActor(null, who == null ? "Unknown user" : who),
                AuditCategory.SIGN_IN,
                "Sign-in refused",
                who,
                null,
                reasonCode,
                null);
    }

    @Transactional
    public void recordSignOut(UUID employeeId, String displayName) {
        audit.record(
                AuditActor.employee(employeeId, displayName),
                AuditCategory.SIGN_IN,
                "Signed out",
                displayName,
                null,
                null,
                null);
    }
}
