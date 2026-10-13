package com.calendar.media.exception;

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

class GlobalErrorHandlerTest {

    private WebTestClient client;

    record Payload(@NotBlank String name) {
    }

    @RestController
    static class TestController {

        @GetMapping("/forbidden")
        Mono<Void> forbidden() {
            return Mono.error(new MediaException(MediaErrorCode.MEDIA_KEY_FORBIDDEN));
        }

        @GetMapping("/too-large")
        Mono<Void> tooLarge() {
            return Mono.error(new MediaException(MediaErrorCode.MEDIA_TOO_LARGE));
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
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler())
                .build();
    }

    @Test
    void handleMediaException_shouldAnswerAsProblemDetail() {
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
                .jsonPath("$.timestamp").exists();
    }

    @Test
    void handleMediaException_shouldCarryTheCodesOwnStatus() {
        client.get().uri("/too-large")
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-BUS-002");
    }

    @Test
    void handleValidationException_shouldListTheOffendingFields() {
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-VAL-001")
                .jsonPath("$.errors.name").exists();
    }

    @Test
    @DisplayName("an unknown path keeps its 404 instead of becoming a 500")
    void handleResponseStatusException_shouldKeepTheOriginalStatus() {
        client.get().uri("/gone")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.code").isEqualTo("MED-REQ-000");
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-TEC-000")
                .jsonPath("$.detail").value(detail -> {
                    if (detail.toString().contains("secret") || detail.toString().contains("garage")) {
                        throw new AssertionError("the original message leaked into the response");
                    }
                });
    }
}
