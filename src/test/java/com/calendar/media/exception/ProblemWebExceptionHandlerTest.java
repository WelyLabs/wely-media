package com.calendar.media.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.HttpHandlerConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemWebExceptionHandlerTest {

    /** Boot's {@code DefaultErrorWebExceptionHandler}: ours must come before it. */
    private static final int BOOT_ERROR_HANDLER_ORDER = -1;

    private SimpleMeterRegistry meterRegistry;
    private ProblemWebExceptionHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new ProblemWebExceptionHandler(new GlobalErrorHandler(meterRegistry),
                new ProblemDetailWriter(JsonMapper.builder().build()));
    }

    /** A server whose only filter fails before any controller is reached. */
    private WebTestClient failingInAFilter(RuntimeException failure) {
        WebFilter failing = (exchange, chain) -> Mono.error(failure);
        return WebTestClient.bindToServer(new HttpHandlerConnector(
                        WebHttpHandlerBuilder.webHandler(exchange -> Mono.empty())
                                .filter(failing)
                                .exceptionHandler(handler)
                                .build()))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @DisplayName("a technical failure raised in a web filter answers our 502 problem and is counted")
    void handle_shouldAnswerTheTechnicalProblem_whenAWebFilterFails() {
        failingInAFilter(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE))
                .get().uri("/internal/media")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-TEC-001")
                .jsonPath("$.instance").isEqualTo("/internal/media")
                .jsonPath("$.requestId").isNotEmpty();

        assertThat(meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, "MED-TEC-001").counter().count()).isEqualTo(1.0);
    }

    @Test
    void handle_shouldAnswerTheBusinessProblem_whenAWebFilterRefuses() {
        failingInAFilter(new BusinessException(BusinessErrorCode.MEDIA_KEY_FORBIDDEN))
                .get().uri("/internal/media")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-BUS-003")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void handle_shouldAnswerTheUnexpectedProblem_whenAWebFilterFailsUnexpectedly() {
        failingInAFilter(new IllegalStateException("boom"))
                .get().uri("/internal/media")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .jsonPath("$.detail").isEqualTo("The request could not be completed.");

        assertThat(meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.UNEXPECTED_FAILURE_CODE).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a response already committed cannot be rewritten: the failure is passed on")
    void handle_shouldPassTheFailureOn_whenTheResponseIsCommitted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/media"));
        StepVerifier.create(exchange.getResponse().setComplete()).expectComplete().verify(Duration.ofSeconds(10));
        IllegalStateException failure = new IllegalStateException("late");

        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorMatches(error -> error == failure)
                .verify(Duration.ofSeconds(10));
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void handle_shouldBeOrderedBeforeBootsErrorHandler() {
        assertThat(OrderUtils.getOrder(ProblemWebExceptionHandler.class)).isLessThan(BOOT_ERROR_HANDLER_ORDER);
    }
}
