package com.calendar.media.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdsTest {

    @Test
    void of_shouldReturnThePropagatedId_whenTheFilterKeptOne() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        exchange.getAttributes().put(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-propagated");

        assertThat(RequestIds.of(exchange)).isEqualTo("abcd1234-propagated");
    }

    @Test
    void of_shouldFallBackToTheServerRequestId_whenNoFilterRan() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        assertThat(RequestIds.of(exchange)).isEqualTo(exchange.getRequest().getId()).isNotBlank();
    }
}
