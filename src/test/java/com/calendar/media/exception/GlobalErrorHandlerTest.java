package com.calendar.media.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalErrorHandlerTest {

    private WebTestClient client;
    private SimpleMeterRegistry meterRegistry;

    record Payload(@NotBlank String name) {
    }

    @RestController
    static class TestController {

        @GetMapping("/forbidden")
        Mono<Void> forbidden() {
            return Mono.error(new BusinessException(BusinessErrorCode.MEDIA_KEY_FORBIDDEN));
        }

        @GetMapping("/too-large")
        Mono<Void> tooLarge() {
            return Mono.error(new BusinessException(BusinessErrorCode.MEDIA_TOO_LARGE));
        }

        @GetMapping("/storage-down")
        Mono<Void> storageDown() {
            return Mono.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE));
        }

        @GetMapping("/generic-error")
        Mono<Void> genericError() {
            return Mono.error(new IllegalStateException("http://GK123:secret@garage:3900"));
        }

        @GetMapping("/gone")
        Mono<Void> gone() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @PostMapping("/validated")
        Mono<Void> validated(@Valid @RequestBody Payload payload) {
            return Mono.empty();
        }
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler(meterRegistry))
                .build();
    }

    @Test
    void handleBusinessException_shouldAnswerAsProblemDetail() {
        client.get().uri("/forbidden")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Key not allowed")
                .jsonPath("$.code").isEqualTo("MED-BUS-003")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/med-bus-003")
                .jsonPath("$.instance").isEqualTo("/forbidden")
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    void handleBusinessException_shouldCarryTheCodesOwnStatus() {
        client.get().uri("/too-large")
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-BUS-002");
    }

    @Test
    @DisplayName("a dependency outage answers 502 with its own technical code")
    void handleTechnicalException_shouldAnswerAsProblemDetail() {
        client.get().uri("/storage-down")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.title").isEqualTo("Storage unavailable")
                .jsonPath("$.detail").isEqualTo("The object storage could not be reached; try again shortly.")
                .jsonPath("$.code").isEqualTo("MED-TEC-001")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/med-tec-001")
                .jsonPath("$.instance").isEqualTo("/storage-down")
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    void handleTechnicalException_shouldCountTheFailureByCode() {
        client.get().uri("/storage-down").exchange().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
        client.get().uri("/storage-down").exchange().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(technicalErrors("MED-TEC-001")).isEqualTo(2.0);
        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag("code", GlobalErrorHandler.UNEXPECTED_CODE).counter()).isNull();
    }

    @Test
    void handleValidationException_shouldListTheOffendingFields() {
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_CODE)
                .jsonPath("$.errors.name").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("an unknown path keeps its 404 instead of becoming a 500")
    void handleResponseStatusException_shouldKeepTheOriginalStatus() {
        client.get().uri("/gone")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank)
                .jsonPath("$.detail").value(detail -> {
                    if (detail.toString().contains("secret") || detail.toString().contains("garage")) {
                        throw new AssertionError("the original message leaked into the response");
                    }
                });
    }

    @Test
    void handleUnexpectedException_shouldCountTheFailureAsTec000() {
        client.get().uri("/generic-error").exchange().expectStatus().is5xxServerError();

        assertThat(technicalErrors(GlobalErrorHandler.UNEXPECTED_CODE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a business refusal is not a technical failure and is not counted")
    void handleBusinessException_shouldNotCountATechnicalFailure() {
        client.get().uri("/forbidden").exchange().expectStatus().isForbidden();

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    private double technicalErrors(String code) {
        return meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).tag("code", code).counter().count();
    }

    private static void assertNotBlank(Object value) {
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value).isNotBlank();
    }
}
