package com.krztechnologies.attend.auth.service;

import com.krztechnologies.attend.access.service.AccessRequestService;
import com.krztechnologies.attend.access.service.UserAccessService;
import com.krztechnologies.attend.auth.domain.AccessState;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decides which screen a signed-in user gets: the app, "no access" (with the request form), pending, or disabled. */
@Service
public class AccessStateResolver {

    private final EmployeeIdentityService identities;
    private final UserAccessService access;
    private final AccessRequestService requests;

    public AccessStateResolver(
            EmployeeIdentityService identities, UserAccessService access, AccessRequestService requests) {
        this.identities = identities;
        this.access = access;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public AccessState resolve(UUID employeeId) {
        var identity = identities.find(employeeId);
        if (identity.isEmpty()) {
            return AccessState.NOT_IN_DIRECTORY;
        }
        if (!identity.get().accountEnabled()) {
            return AccessState.ACCOUNT_DISABLED;
        }
        if (access.hasAccess(employeeId)) {
            return AccessState.ACTIVE;
        }
        boolean pending = requests.latestFor(employeeId)
                .map(r -> "PENDING".equals(r.status()))
                .orElse(false);
        return pending ? AccessState.REQUEST_PENDING : AccessState.NO_ROLE;
    }
}
