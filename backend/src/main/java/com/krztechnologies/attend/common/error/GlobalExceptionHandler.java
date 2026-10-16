package com.krztechnologies.attend.common.error;

import com.krztechnologies.attend.common.web.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception that reaches a controller to the error envelope {code, message, fieldErrors, traceId}.
 * Unexpected exceptions are logged once, here, with the trace ID; the client never sees a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException ex) {
        return status(ex.status(), ex.code(), ex.getMessage(), ex.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        return status(HttpStatus.BAD_REQUEST, "VALIDATION", "Some fields are not valid.", fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParameters(HandlerMethodValidationException ex) {
        List<ErrorResponse.FieldError> fields = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new ErrorResponse.FieldError(
                                r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        return status(HttpStatus.BAD_REQUEST, "VALIDATION", "Some fields are not valid.", fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException ex) {
        List<ErrorResponse.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return status(HttpStatus.BAD_REQUEST, "VALIDATION", "Some fields are not valid.", fields);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ErrorResponse> malformed(Exception ex) {
        return status(
                HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request is malformed or has unknown fields.", List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> staleVersion(OptimisticLockingFailureException ex) {
        return status(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                "Someone else changed this. Reload to see the latest.",
                List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException ex) {
        return status(HttpStatus.FORBIDDEN, "FORBIDDEN", "You don't have permission to do this.", List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException ex) {
        return status(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", "The upload is too large.", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return status(
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This method is not allowed here.", List.of());
    }

    /** Also a file upload sent as something other than multipart/form-data (the size limit is handled above). */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, MultipartException.class})
    ResponseEntity<ErrorResponse> unsupportedMediaType(Exception ex) {
        return status(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "This content type is not accepted.",
                List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException ex) {
        return status(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.", List.of());
    }

    /**
     * The client went away (a closed tab on an event stream): nothing can be written any more and nothing went wrong,
     * so there is no response and no error log.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone(AsyncRequestNotUsableException ex) {
        LOG.debug("Client disconnected: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        LOG.error("Unhandled exception", ex);
        return status(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Something went wrong. Quote the trace ID if you report it.",
                List.of());
    }

    /** Always JSON, also when the client asked for something else (an event stream, a CSV download). */
    private static ResponseEntity<ErrorResponse> status(
            HttpStatusCode status, String code, String message, List<ErrorResponse.FieldError> fields) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(code, message, fields));
    }

    private static ErrorResponse body(String code, String message, List<ErrorResponse.FieldError> fields) {
        return new ErrorResponse(code, message, fields, TraceIdFilter.currentTraceId());
    }
}
