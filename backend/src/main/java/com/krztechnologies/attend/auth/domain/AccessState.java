package com.krztechnologies.attend.auth.domain;

/** The user's access state, returned by {@code /me}; the frontend routes each to its screen. */
public enum AccessState {
    ACTIVE,
    NO_ROLE,
    REQUEST_PENDING,
    ACCOUNT_DISABLED,
    NOT_IN_DIRECTORY
}
