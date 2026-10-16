package com.krztechnologies.attend.common.security;

import java.util.UUID;

/** Implemented by the signed-in principal (the auth module's OIDC user): links the session to an employee. */
public interface AttendPrincipal {

    UUID employeeId();

    String displayName();
}
