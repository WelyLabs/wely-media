package com.calendar.media.application.security;

import com.calendar.media.exception.ProblemDetailWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailAuthenticationEntryPointTest {

    private final ProblemDetailAuthenticationEntryPoint entryPoint =
            new ProblemDetailAuthenticationEntryPoint(new ProblemDetailWriter(JsonMapper.builder().build()));

    @Test
    void commence_shouldAnswer401WithAProblemDetail() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/internal/media"));

        StepVerifier.create(entryPoint.commence(exchange, new BadCredentialsException("x"))).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"MED-REQ-001\"")
                        .contains("\"status\":401")
                        .contains("\"instance\":\"/internal/media\"")
                        .contains("\"type\":\"https://welylabs.app/problems/med-req-001\"")
                        .contains("\"timestamp\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete().verify(Duration.ofSeconds(10));
    }
}
