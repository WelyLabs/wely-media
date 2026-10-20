package com.calendar.media.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsTest {

    @Test
    void of_shouldBuildTheProblemFromAnErrorCode() {
        ProblemDetail problem = ProblemDetails.of(BusinessErrorCode.CLIENT_UNKNOWN, "/internal/media");

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo(BusinessErrorCode.CLIENT_UNKNOWN.getTitle());
        assertThat(problem.getDetail()).isEqualTo(BusinessErrorCode.CLIENT_UNKNOWN.getDetail());
        assertThat(problem.getType()).isEqualTo(URI.create("https://welylabs.app/problems/med-req-001"));
        assertThat(problem.getInstance()).isEqualTo(URI.create("/internal/media"));
        assertThat(problem.getProperties()).containsEntry("code", "MED-REQ-001").containsKey("timestamp");
    }

    @Test
    void of_shouldBuildTheProblemFromExplicitFields() {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_GATEWAY, "MED-TEC-001", "t", "d", "/x");

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getProperties()).containsEntry("code", "MED-TEC-001");
    }

    @Test
    void of_shouldAddTheRequestId_whenBuiltFromAnExchangeAndAnErrorCode() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/media"));

        ProblemDetail problem = ProblemDetails.of(BusinessErrorCode.CLIENT_UNKNOWN, exchange);

        assertThat(problem.getInstance()).isEqualTo(URI.create("/internal/media"));
        assertThat(problem.getProperties())
                .containsEntry("code", "MED-REQ-001")
                .containsEntry(ProblemDetails.REQUEST_ID_PROPERTY, exchange.getRequest().getId());
    }

    @Test
    void of_shouldAddTheRequestId_whenBuiltFromAnExchangeAndExplicitFields() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_GATEWAY, "MED-TEC-001", "t", "d", exchange);

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getInstance()).isEqualTo(URI.create("/x"));
        assertThat((String) problem.getProperties().get(ProblemDetails.REQUEST_ID_PROPERTY)).isNotBlank()
                .isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void of_shouldNotAddARequestId_whenBuiltFromAPath() {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_GATEWAY, "MED-TEC-001", "t", "d", "/x");

        assertThat(problem.getProperties()).doesNotContainKey(ProblemDetails.REQUEST_ID_PROPERTY);
    }
}
