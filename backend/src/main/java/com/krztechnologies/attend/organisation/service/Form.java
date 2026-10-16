package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.error.ErrorResponse.FieldError;
import com.krztechnologies.attend.common.error.Errors;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Collects the field messages of an organisation editor (the prototype's {@code data-err} texts) and refuses the
 * request with 422 {@code FIELDS_INVALID} and one {@code fieldErrors} entry per problem.
 */
final class Form {

    static final Pattern SHORT_CODE = Pattern.compile("^[A-Z0-9]{2,6}$");

    private final List<FieldError> errors = new ArrayList<>();

    /** Adds the message when {@code problem} is true; returns whether the field is still fine. */
    boolean check(boolean problem, String field, String message) {
        if (problem) {
            add(field, message);
        }
        return !problem;
    }

    void add(String field, String message) {
        if (errors.stream().noneMatch(e -> e.field().equals(field))) {
            errors.add(new FieldError(field, message));
        }
    }

    void addAll(List<FieldError> more) {
        more.forEach(e -> add(e.field(), e.message()));
    }

    boolean has(String field) {
        return errors.stream().anyMatch(e -> e.field().equals(field));
    }

    void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new Errors.BusinessRule("FIELDS_INVALID", "Some fields need your attention.", errors);
        }
    }

    /** Trimmed text; null for blank. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static Errors.Conflict stale() {
        return new Errors.Conflict("STALE_VERSION", "Someone else changed this. Reload to see the latest.");
    }
}
