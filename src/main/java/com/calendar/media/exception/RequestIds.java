package com.calendar.media.exception;

import org.springframework.web.server.ServerWebExchange;

/**
 * The id that ties a response to its log lines: the one the gateway propagated, as kept by
 * {@code RequestIdWebFilter}, or this server's own request id when there is none.
 */
public final class RequestIds {

    /** Carries the id between the gateway and the services, and back to the caller. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Exchange attribute and Reactor Context key under which the filter keeps the id. */
    public static final String REQUEST_ID_ATTRIBUTE = "wely.requestId";

    private RequestIds() {
    }

    /** Falls back to the WebFlux request id when the filter did not run (a bare handler, a test). */
    public static String of(ServerWebExchange exchange) {
        String propagated = exchange.getAttribute(REQUEST_ID_ATTRIBUTE);
        return propagated != null ? propagated : exchange.getRequest().getId();
    }
}
