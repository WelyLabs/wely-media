package com.calendar.media.application.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InternalSecretAuthenticationConverterTest {

    private final InternalSecretAuthenticationConverter converter = new InternalSecretAuthenticationConverter();

    @Test
    void convert_shouldCarryTheSecretAsUnauthenticatedCredentials() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/internal/media").header("X-Internal-Secret", "s3cret"));

        StepVerifier.create(converter.convert(exchange))
                .assertNext(authentication -> {
                    assertThat(authentication.isAuthenticated()).isFalse();
                    assertThat(authentication.getCredentials()).isEqualTo("s3cret");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void convert_shouldAttemptNothing_whenTheHeaderIsMissing() {
        StepVerifier.create(converter.convert(MockServerWebExchange.from(MockServerHttpRequest.get("/internal/media"))))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void convert_shouldAttemptNothing_whenTheHeaderIsBlank() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/internal/media").header("X-Internal-Secret", " "));

        StepVerifier.create(converter.convert(exchange)).expectComplete().verify(Duration.ofSeconds(10));
    }
}
