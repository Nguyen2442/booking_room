package com.company.booking.common;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves the API as application/problem+json with {@code code} and {@code requestId}. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final MeterRegistry metrics;

    public GlobalExceptionHandler(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        enrich(pd);
        return pd;
    }

    static void enrich(ProblemDetail pd) {
        Map<String, Object> props = pd.getProperties();
        if (props == null || !props.containsKey("code")) {
            pd.setProperty("code", HttpStatus.valueOf(pd.getStatus()).name());
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
    }

    private static ResponseEntity<Object> response(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        enrich(pd);
        return ResponseEntity.status(status).body(pd);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setTitle(ex.getStatus().getReasonPhrase());
        pd.setProperty("code", ex.getCode());
        ex.getProperties().forEach(pd::setProperty);
        enrich(pd);
        // one counter per business error code: spikes of RATE_LIMITED / quota errors are visible on a dashboard
        metrics.counter("api.errors", "code", ex.getCode(), "status", String.valueOf(ex.getStatus().value())).increment();
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    /**
     * The database is busy (a lock was not granted within 5 s, a statement hit its timeout, or the pool is
     * exhausted): tell the client to retry instead of letting the request hang or fail with an opaque 500.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class,
            DataAccessResourceFailureException.class})
    public ResponseEntity<Object> handleBusy(Exception ex) {
        log.warn("Database busy or unavailable: {}", ex.toString());
        ResponseEntity<Object> r = response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                "The system is busy, please try again in a moment");
        return ResponseEntity.status(r.getStatusCode()).header("Retry-After", "2").body(r.getBody());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return response(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by someone else, please retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Unhandled data integrity violation", ex);
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data");
    }

    /** e.g. ?sort=doesNotExist */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Object> handleBadSort(PropertyReferenceException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Unknown sort property: " + ex.getPropertyName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(e -> Map.of(
                        "field", e instanceof org.springframework.validation.FieldError fe ? fe.getField() : e.getObjectName(),
                        "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        pd.setProperty("code", "VALIDATION_FAILED");
        pd.setProperty("errors", errors);
        enrich(pd);
        return ResponseEntity.badRequest().body(pd);
    }

    /** Framework-raised errors (bad JSON, bad parameter type, 405, 415 ...) get code + requestId too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> result = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (result != null && result.getBody() instanceof ProblemDetail pd) {
            enrich(pd);
        }
        return result;
    }
}
