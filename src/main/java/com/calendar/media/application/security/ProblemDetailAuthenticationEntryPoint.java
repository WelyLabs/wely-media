package com.calendar.media.application.security;

import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.ProblemDetailWriter;
import com.calendar.media.exception.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers 401 MED-REQ-001 in the same RFC 7807 shape as the controller advice, request id included.
 *
 * <p>No {@code WWW-Authenticate} header: callers authenticate with {@code X-Internal-Secret}, which
 * is no HTTP authentication scheme a client could be challenged with.
 */
public class ProblemDetailAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint.class);

    private final ProblemDetailWriter writer;

    public ProblemDetailAuthenticationEntryPoint(ProblemDetailWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return Mono.defer(() -> {
            BusinessErrorCode error = BusinessErrorCode.CLIENT_UNKNOWN;
            // Expected outcome, not an incident: WARN, without a stack trace.
            log.warn("Refused {} on {} (request {})", error.getCode(), exchange.getRequest().getPath().value(),
                    exchange.getRequest().getId());
            return writer.write(exchange, ProblemDetails.of(error, exchange));
        });
    }
}
