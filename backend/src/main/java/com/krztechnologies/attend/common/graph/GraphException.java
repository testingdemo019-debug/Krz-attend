package com.krztechnologies.attend.common.graph;

/**
 * A Microsoft Graph or token call failed. Carries Graph's own error code (e.g. {@code ErrorAccessDenied},
 * {@code Authorization_RequestDenied}, {@code invalid_client}) so it can be shown to an administrator; never the
 * secret or the token.
 */
public class GraphException extends RuntimeException {

    private final int status;
    private final String code;

    public GraphException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public GraphException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** HTTP status, or 0 when Graph could not be reached. */
    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
