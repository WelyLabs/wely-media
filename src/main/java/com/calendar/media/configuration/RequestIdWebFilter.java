package com.calendar.media.configuration;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.regex.Pattern;

import static com.calendar.media.exception.RequestIds.REQUEST_ID_ATTRIBUTE;
import static com.calendar.media.exception.RequestIds.REQUEST_ID_HEADER;

/**
 * Keeps the gateway's {@code X-Request-Id}, so one id follows a request from the gateway to this
 * service's problems and logs; stores it as an exchange attribute and in the Reactor Context, and
 * answers it in the response header.
 *
 * <p>Runs first, before the security chain, so that a 401 carries the id too. A header that is
 * missing or does not look like an id is replaced by this server's own request id: the value ends up
 * in logs, so it is never trusted as sent.
 *
 * <p>Not forwarded anywhere: this service calls no other Wely service, and Garage, an external
 * store, must not receive it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    static final Pattern ACCEPTED_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String sent = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String requestId = sent != null && ACCEPTED_ID.matcher(sent).matches() ? sent : exchange.getRequest().getId();

        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange).contextWrite(Context.of(REQUEST_ID_ATTRIBUTE, requestId));
    }
}
