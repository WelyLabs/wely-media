package com.calendar.media.exception;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Answers our problem for a failure that never reached a controller — raised in a web filter or
 * before handler mapping — which would otherwise get Boot's own error JSON, without {@code code}
 * or {@code requestId}.
 *
 * <p>The mapping is {@link GlobalErrorHandler}'s, so the code, status, log level and counter are
 * the same whichever way a failure arrives.
 */
@Component
@Order(ProblemWebExceptionHandler.ORDER)
public class ProblemWebExceptionHandler implements WebExceptionHandler {

    /** Before Boot's {@code DefaultErrorWebExceptionHandler} (-1) and WebFlux's status handler (0). */
    static final int ORDER = -2;

    private final GlobalErrorHandler errorHandler;
    private final ProblemDetailWriter writer;

    public ProblemWebExceptionHandler(GlobalErrorHandler errorHandler, ProblemDetailWriter writer) {
        this.errorHandler = errorHandler;
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // Status and headers are gone: nothing can be rewritten, let the server close the exchange.
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        return writer.write(exchange, errorHandler.handle(ex, exchange));
    }
}
