package com.calendar.media.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.time.Instant;

/** The one RFC 7807 shape, shared by the controller advice and the security entry point. */
public final class ProblemDetails {

    /** Namespace for the {@code type} URI. Not dereferenced; it identifies, it does not serve. */
    static final String PROBLEM_TYPE_BASE = "https://welylabs.app/problems/";

    /** The WebFlux request id, the same one that prefixes the framework's log lines. */
    public static final String REQUEST_ID = "requestId";

    private ProblemDetails() {
    }

    public static ProblemDetail of(BusinessErrorCode error, String path) {
        return of(HttpStatusCode.valueOf(error.getStatus()), error.getCode(), error.getTitle(), error.getDetail(), path);
    }

    /** Same as {@link #of(BusinessErrorCode, String)}, with the request id to correlate with the logs. */
    public static ProblemDetail of(BusinessErrorCode error, ServerWebExchange exchange) {
        return withRequestId(of(error, path(exchange)), exchange);
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase()));
        problem.setTitle(title);
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    /** Same as {@link #of(HttpStatusCode, String, String, String, String)}, with the request id. */
    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail,
                                   ServerWebExchange exchange) {
        return withRequestId(of(status, code, title, detail, path(exchange)), exchange);
    }

    private static ProblemDetail withRequestId(ProblemDetail problem, ServerWebExchange exchange) {
        problem.setProperty(REQUEST_ID, exchange.getRequest().getId());
        return problem;
    }

    private static String path(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}
