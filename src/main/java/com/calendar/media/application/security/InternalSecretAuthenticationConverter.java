package com.calendar.media.application.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Reads the caller's secret from {@value #HEADER}; no header, no attempt (the entry point answers 401). */
public class InternalSecretAuthenticationConverter implements ServerAuthenticationConverter {

    public static final String HEADER = "X-Internal-Secret";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String secret = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (secret == null || secret.isBlank()) {
            return Mono.empty();
        }
        return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(null, secret));
    }
}
