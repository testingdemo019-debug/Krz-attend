package com.krztechnologies.attend.common.error;

import java.util.List;

/**
 * The error envelope of every failed API call.
 *
 * @param code stable machine-readable code (e.g. STALE_VERSION, LAST_ADMIN)
 * @param message text for the user
 * @param fieldErrors per-field validation messages (empty when not a validation error)
 * @param traceId correlates the response with the server logs
 */
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors, String traceId) {

    /** A message for one request field. */
    public record FieldError(String field, String message) {}
}
