package com.krztechnologies.attend.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Base of every error the API returns on purpose. The stable {@code code} is part of the API contract; the message
 * is safe to show to users. Stack traces never reach the client.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<ErrorResponse.FieldError> fieldErrors;

    protected ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    protected ApiException(HttpStatus status, String code, String message, List<ErrorResponse.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ErrorResponse.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
