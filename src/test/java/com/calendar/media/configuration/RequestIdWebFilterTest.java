package com.calendar.media.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.calendar.media.exception.RequestIds.REQUEST_ID_ATTRIBUTE;
import static com.calendar.media.exception.RequestIds.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {

    private static final String PROPAGATED = "abcd1234-ef56-7890";

    /** The id as the rest of the chain sees it: attribute and Reactor Context. */
    private record Seen(String attribute, String context) {
    }

    private static Seen filtered(MockServerWebExchange exchange) {
        AtomicReference<Seen> seen = new AtomicReference<>();
        WebFilterChain chain = filteredExchange -> Mono.deferContextual(context -> {
            seen.set(new Seen(filteredExchange.getAttribute(REQUEST_ID_ATTRIBUTE),
                    context.getOrDefault(REQUEST_ID_ATTRIBUTE, null)));
            return Mono.empty();
        });

        StepVerifier.create(new RequestIdWebFilter().filter(exchange, chain))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
        return seen.get();
    }

    private static MockServerWebExchange sending(String requestId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/internal/media");
        if (requestId != null) {
            request.header(REQUEST_ID_HEADER, requestId);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void filter_shouldKeepAValidPropagatedId_everywhere() {
        MockServerWebExchange exchange = sending(PROPAGATED);

        Seen seen = filtered(exchange);

        assertThat(seen.attribute()).isEqualTo(PROPAGATED);
        assertThat(seen.context()).isEqualTo(PROPAGATED);
        assertThat(exchange.getResponse().getHeaders().getFirst(REQUEST_ID_HEADER)).isEqualTo(PROPAGATED);
    }

    @Test
    void filter_shouldFallBackToTheServerId_whenTheHeaderIsMissing() {
        MockServerWebExchange exchange = sending(null);

        Seen seen = filtered(exchange);

        assertThat(seen.attribute()).isEqualTo(exchange.getRequest().getId()).isNotBlank();
        assertThat(seen.context()).isEqualTo(exchange.getRequest().getId());
        assertThat(exchange.getResponse().getHeaders().getFirst(REQUEST_ID_HEADER)).isEqualTo(exchange.getRequest().getId());
    }

    @Test
    @DisplayName("a value that does not look like an id never reaches the logs")
    void filter_shouldFallBackToTheServerId_whenTheHeaderIsNotAnId() {
        for (String rejected : new String[] {"abc-123", "a".repeat(65), "abcd1234\nforged log line", "abcd 1234",
                "<script>12345</script>"}) {
            MockServerWebExchange exchange = sending(rejected);

            Seen seen = filtered(exchange);

            assertThat(seen.attribute()).as(rejected).isEqualTo(exchange.getRequest().getId());
            assertThat(exchange.getResponse().getHeaders().getFirst(REQUEST_ID_HEADER)).as(rejected)
                    .isEqualTo(exchange.getRequest().getId());
        }
    }

    @Test
    void filter_shouldAcceptIdsAtBothLengthLimits() {
        for (String accepted : new String[] {"a".repeat(8), "A1-".repeat(21) + "z"}) {
            assertThat(filtered(sending(accepted)).attribute()).isEqualTo(accepted);
        }
    }
}
