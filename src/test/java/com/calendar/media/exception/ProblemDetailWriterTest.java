package com.calendar.media.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailWriterTest {

    private final ProblemDetailWriter writer = new ProblemDetailWriter(JsonMapper.builder().build());

    @Test
    void write_shouldAnswerTheProblemsStatus_asProblemJson_withItsPropertiesAtTopLevel() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/media"));
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_GATEWAY, "MED-TEC-001", "Storage unavailable",
                "d", exchange);

        StepVerifier.create(writer.write(exchange, problem)).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"type\":\"https://welylabs.app/problems/med-tec-001\"")
                        .contains("\"title\":\"Storage unavailable\"")
                        .contains("\"status\":502")
                        .contains("\"instance\":\"/internal/media\"")
                        .contains("\"code\":\"MED-TEC-001\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"")
                        .doesNotContain("\"properties\""))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }
}
