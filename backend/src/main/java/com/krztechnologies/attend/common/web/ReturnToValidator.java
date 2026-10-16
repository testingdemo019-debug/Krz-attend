package com.krztechnologies.attend.common.web;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates the post-login {@code returnTo}: only a relative path that starts with a single {@code /}, has no scheme,
 * no {@code //}, no backslash, and matches one of the app's routes (protection against open redirects). The frontend
 * applies the same rules.
 */
public final class ReturnToValidator {

    private static final int MAX_LENGTH = 512;
    private static final Pattern SAFE_CHARS = Pattern.compile("^/[A-Za-z0-9/_\\-.]*(\\?[A-Za-z0-9_\\-.=&%,]*)?$");
    private static final List<Pattern> ROUTES = List.of(
            Pattern.compile("^/$"),
            Pattern.compile("^/(dashboard|live|review|events|enrol)$"),
            Pattern.compile("^/attendance/(today|log)$"),
            Pattern.compile("^/employees(/[0-9a-fA-F-]{36})?$"),
            Pattern.compile("^/org/(locations|departments|shifts|allocations|holidays)$"),
            Pattern.compile("^/reports/(coverage|recognition)$"),
            Pattern.compile("^/settings(/[a-z-]+)?$"));

    private ReturnToValidator() {}

    /** The value if it is a safe app path, otherwise empty. */
    public static Optional<String> validate(String returnTo) {
        if (returnTo == null || returnTo.isEmpty() || returnTo.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (returnTo.startsWith("//") || returnTo.contains("\\") || returnTo.contains("://")) {
            return Optional.empty();
        }
        if (!SAFE_CHARS.matcher(returnTo).matches()) {
            return Optional.empty();
        }
        int query = returnTo.indexOf('?');
        String path = query < 0 ? returnTo : returnTo.substring(0, query);
        if (path.contains("..") || path.contains("//")) {
            return Optional.empty();
        }
        return ROUTES.stream().anyMatch(r -> r.matcher(path).matches()) ? Optional.of(returnTo) : Optional.empty();
    }
}
