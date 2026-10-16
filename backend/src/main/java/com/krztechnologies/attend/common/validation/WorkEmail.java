package com.krztechnologies.attend.common.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Work e-mail rules: a syntactically plain address of at most 254 characters in the allowed domain
 * (ENTRA_ALLOWED_DOMAIN). Used for the sign-in {@code login_hint}; anything else is dropped.
 */
public final class WorkEmail {

    private static final int MAX_LENGTH = 254;
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,189}$");

    private WorkEmail() {}

    public static boolean isWorkEmail(String value, String allowedDomain) {
        if (value == null
                || value.length() > MAX_LENGTH
                || !ADDRESS.matcher(value).matches()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).endsWith("@" + allowedDomain.toLowerCase(Locale.ROOT));
    }
}
