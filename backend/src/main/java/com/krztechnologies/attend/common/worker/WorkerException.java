package com.krztechnologies.attend.common.worker;

import com.krztechnologies.attend.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A call to the recognition worker failed. The code is the worker's own error code ({@code AUTH_FAILED},
 * {@code TIMEOUT}, {@code UNREACHABLE}, {@code UNSUPPORTED_CODEC}, {@code MODEL_NOT_READY}, {@code BAD_REQUEST}, …), or
 * {@code WORKER_UNREACHABLE}, {@code WORKER_TIMEOUT}, {@code WORKER_TOKEN_REFUSED} or {@code WORKER_ERROR} when the
 * worker itself did not answer properly. The message is safe to show: never a token, an RTSP credential or image data.
 */
public class WorkerException extends ApiException {

    public WorkerException(String code) {
        super(statusOf(code), code, describe(code));
    }

    /** The user-facing text for a worker error code (also used for a failed camera test). */
    public static String describe(String code) {
        return switch (code) {
            case "AUTH_FAILED" -> "Authentication failed — check the camera user name and password.";
            case "TIMEOUT" -> "The camera did not answer in time.";
            case "UNREACHABLE" -> "The camera could not be reached on the network.";
            case "UNSUPPORTED_CODEC" -> "The camera sends a video format that is not supported.";
            case "MODEL_NOT_READY" -> "The recognition worker is still loading its models. Try again in a minute.";
            case "NO_FACE" -> "No face was found.";
            case "MULTIPLE_FACES" -> "More than one face was found.";
            case "LOW_QUALITY" -> "The image quality is too low.";
            case "BAD_REQUEST" -> "The recognition worker refused the request.";
            case "WORKER_UNREACHABLE" -> "The recognition worker is not reachable.";
            case "WORKER_TIMEOUT" -> "The recognition worker did not answer in time.";
            case "WORKER_TOKEN_REFUSED" -> "The recognition worker refused the backend's token (WORKER_API_TOKEN).";
            default -> "The recognition worker reported an error.";
        };
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case "WORKER_UNREACHABLE", "MODEL_NOT_READY" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "WORKER_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}
