package com.calendar.media.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a {@link ProblemDetail} straight to the response, for the paths that answer without a
 * controller: the security entry point and {@link ProblemWebExceptionHandler}.
 */
@Component
public class ProblemDetailWriter {

    private final ObjectMapper mapper;

    public ProblemDetailWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        return Mono.defer(() -> {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            byte[] body = mapper.writeValueAsBytes(flatten(problem));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        });
    }

    /** The standard fields then the extra properties at top level, as the HTTP codecs write them. */
    private static Map<String, Object> flatten(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", String.valueOf(problem.getType()));
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", String.valueOf(problem.getInstance()));
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
