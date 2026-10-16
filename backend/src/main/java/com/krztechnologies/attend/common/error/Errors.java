package com.krztechnologies.attend.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/** The concrete API exceptions (ApiException hierarchy). */
public final class Errors {

    private Errors() {}

    /** 404: the resource does not exist (or is not visible). */
    public static final class NotFound extends ApiException {
        public NotFound(String message) {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
        }
    }

    /** 409: a conflict with the current state, e.g. STALE_VERSION or LAST_ADMIN. */
    public static final class Conflict extends ApiException {
        public Conflict(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }

    /** 422: a domain rule refused the request. */
    public static final class BusinessRule extends ApiException {
        public BusinessRule(String code, String message) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
        }

        public BusinessRule(String code, String message, List<ErrorResponse.FieldError> fieldErrors) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, code, message, fieldErrors);
        }
    }

    /** 400: a request that is malformed in a way Bean Validation cannot express. */
    public static final class BadRequest extends ApiException {
        public BadRequest(String code, String message) {
            super(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    /** 410: the resource existed but was deleted for good, e.g. a crop after the retention period. */
    public static final class Gone extends ApiException {
        public Gone(String code, String message) {
            super(HttpStatus.GONE, code, message);
        }
    }

    /** 403: the user lacks a permission or a data-level right. */
    public static final class Forbidden extends ApiException {
        public Forbidden(String code, String message) {
            super(HttpStatus.FORBIDDEN, code, message);
        }
    }
}
