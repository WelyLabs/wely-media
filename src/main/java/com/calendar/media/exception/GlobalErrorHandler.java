package com.calendar.media.exception;

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
 * {@code code} a caller branches on and a {@code timestamp} to correlate with logs.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(MediaException.class)
    public ProblemDetail handleMediaException(MediaException ex, ServerWebExchange exchange) {
        MediaErrorCode error = ex.getErrorCode();
        // Expected outcome, not an incident: WARN, without a stack trace.
        log.warn("Refused {} on {}", error.getCode(), path(exchange));
        return ProblemDetails.of(error, path(exchange));
    }

    /** Bean Validation rejections, with the offending fields listed. */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidationException(WebExchangeBindException ex, ServerWebExchange exchange) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "MED-VAL-001",
                "Invalid request", "One or more fields are invalid.", path(exchange));
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
        log.debug("{} on {}", status, path(exchange));
        return ProblemDetails.of(status, "MED-REQ-000", status.getReasonPhrase(),
                "The request could not be served as sent.", path(exchange));
    }

    /** Last resort: the detail stays generic, the real message goes to the logs. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled failure on {}", path(exchange), ex);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "MED-TEC-000",
                "Unexpected error", "The request could not be completed.", path(exchange));
    }

    private static String path(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}
