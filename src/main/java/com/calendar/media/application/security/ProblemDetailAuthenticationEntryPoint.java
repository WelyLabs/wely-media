package com.calendar.media.application.security;

import com.calendar.media.exception.MediaErrorCode;
import com.calendar.media.exception.ProblemDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Answers 401 MED-REQ-001 in the same RFC 7807 shape as the controller advice. */
public class ProblemDetailAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return Mono.defer(() -> {
            ProblemDetail problem = ProblemDetails.of(MediaErrorCode.CLIENT_UNKNOWN, exchange.getRequest().getPath().value());
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            byte[] body = mapper.writeValueAsBytes(flatten(problem));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        });
    }

    /** The standard fields then the extra properties at top level, as the HTTP codecs write them. */
    private static Map<String, Object> flatten(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
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
