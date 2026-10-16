package com.krztechnologies.attend.auth.domain;

/** Session attribute names used by sign-in and the session policy. */
public final class SessionKeys {

    /** When the user signed in (Instant); drives the absolute lifetime. */
    public static final String SIGNED_IN_AT = "krz.signedInAt";

    /** The last user-initiated activity (Instant); drives the idle timeout. */
    public static final String LAST_USER_ACTIVITY = "krz.lastUserActivity";

    /** The validated post-login path, stored when the sign-in starts. */
    public static final String RETURN_TO = "krz.returnTo";

    private SessionKeys() {}
}
