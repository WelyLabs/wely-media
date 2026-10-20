package com.calendar.media.exception;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every failure into an RFC 7807 {@code application/problem+json} response, with a stable
 * {@code code} a caller branches on, a {@code timestamp} and the {@code requestId} that also
 * appears on every log line written here, so a response can be tied to its logs.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    /** Bean Validation rejections; not in an enum because the framework, not this service, raises them. */
    public static final String VALIDATION_CODE = "MED-VAL-001";
    /** Statuses the framework decided (unknown path, wrong method, unreadable body). */
    public static final String REQUEST_CODE = "MED-REQ-000";
    /** The catch-all: a failure nothing else recognised. */
    public static final String UNEXPECTED_CODE = "MED-TEC-000";

    /** Counts every technical failure answered, tagged by {@code code}; scraped as {@code wely_errors_technical_total}. */
    static final String TECHNICAL_ERRORS_METRIC = "wely.errors.technical";

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final MeterRegistry meterRegistry;

    public GlobalErrorHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex, ServerWebExchange exchange) {
        BusinessErrorCode error = ex.getErrorCode();
        // Expected outcome, not an incident: WARN, without a stack trace.
        log.warn("Refused {} on {} (request {})", error.getCode(), path(exchange), requestId(exchange));
        return ProblemDetails.of(error, exchange);
    }

    @ExceptionHandler(TechnicalException.class)
    public ProblemDetail handleTechnicalException(TechnicalException ex, ServerWebExchange exchange) {
        TechnicalErrorCode error = ex.getErrorCode();
        // A dependency is down: ERROR with the cause, because someone has to act on it.
        // The previous version logged these at WARN, which hid them.
        log.error("Technical failure {} on {} (request {})", error.getCode(), path(exchange), requestId(exchange), ex);
        countTechnicalError(error.getCode());
        return ProblemDetails.of(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(), exchange);
    }

    /** Bean Validation rejections, with the offending fields listed. */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidationException(WebExchangeBindException ex, ServerWebExchange exchange) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, VALIDATION_CODE,
                "Invalid request", "One or more fields are invalid.", exchange);
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /**
     * Statuses the framework decided (unknown path, wrong method, unreadable body). Without this,
     * the catch-all below would answer 500 for all of them.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        log.debug("{} on {} (request {})", status, path(exchange), requestId(exchange));
        return ProblemDetails.of(status, REQUEST_CODE, status.getReasonPhrase(),
                "The request could not be served as sent.", exchange);
    }

    /** Last resort: the detail stays generic, the real message goes to the logs. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled failure on {} (request {})", path(exchange), requestId(exchange), ex);
        countTechnicalError(UNEXPECTED_CODE);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_CODE,
                "Unexpected error", "The request could not be completed.", exchange);
    }

    private void countTechnicalError(String code) {
        meterRegistry.counter(TECHNICAL_ERRORS_METRIC, "code", code).increment();
    }

    private static String path(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }

    private static String requestId(ServerWebExchange exchange) {
        return exchange.getRequest().getId();
    }
}
