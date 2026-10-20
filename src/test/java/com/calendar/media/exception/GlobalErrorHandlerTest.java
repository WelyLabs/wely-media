package com.calendar.media.exception;

import com.calendar.media.domain.services.MediaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;

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
                .tag(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.UNEXPECTED_FAILURE_CODE).counter()).isNull();
    }

    @Test
    void handleValidationException_shouldListTheOffendingFields() {
        client.post().uri("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_FAILED_CODE)
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
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
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

        assertThat(technicalErrors(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a business refusal is not a technical failure and is not counted")
    void handleBusinessException_shouldNotCountATechnicalFailure() {
        client.get().uri("/forbidden").exchange().expectStatus().isForbidden();

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    private ProblemDetail handled(Throwable failure) {
        return new GlobalErrorHandler(meterRegistry)
                .handle(failure, MockServerWebExchange.from(MockServerHttpRequest.get("/internal/media")));
    }

    @Test
    @DisplayName("a bare Throwable is mapped exactly as its own handler would")
    void handle_shouldMapEachFailureLikeItsHandler() throws NoSuchMethodException {
        WebExchangeBindException invalid = new WebExchangeBindException(
                new MethodParameter(Object.class.getMethod("equals", Object.class), 0),
                new BeanPropertyBindingResult(new Payload(""), "payload"));

        assertThat(handled(new BusinessException(BusinessErrorCode.MEDIA_KEY_FORBIDDEN)).getProperties())
                .containsEntry("code", "MED-BUS-003");
        assertThat(handled(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)).getStatus()).isEqualTo(502);
        assertThat(handled(invalid).getProperties()).containsEntry("code", GlobalErrorHandler.VALIDATION_FAILED_CODE);
        assertThat(handled(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED)).getProperties())
                .containsEntry("code", GlobalErrorHandler.REQUEST_REFUSED_CODE);
        ProblemDetail error = handled(new AssertionError("not even an Exception"));
        assertThat(error.getStatus()).isEqualTo(500);
        assertThat(error.getProperties()).containsEntry("code", GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .containsKey(ProblemDetails.REQUEST_ID_PROPERTY);

        assertThat(technicalErrors("MED-TEC-001")).isEqualTo(1.0);
        assertThat(technicalErrors(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    private double technicalErrors(String code) {
        return meterRegistry.get(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, code).counter().count();
    }

    private static void assertNotBlank(Object value) {
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value).isNotBlank();
    }

    /**
     * The same failures in the running application, where Boot's own handlers are registered too:
     * binding only this advice, as above, cannot show which one answers.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    class InTheRunningApplication {

        private static final String SECRET = "users-test-secret";

        // Same mock as the other web tests, so they all share one context.
        @MockitoBean
        private MediaService mediaService;

        @Value("${local.server.port}")
        private int port;

        private WebTestClient server() {
            return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .defaultHeader("X-Internal-Secret", SECRET)
                    .build();
        }

        private static void expectProblem(WebTestClient.ResponseSpec response, int status, String code) {
            response.expectStatus().isEqualTo(status)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(status)
                    .jsonPath("$.code").isEqualTo(code)
                    .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
        }

        @Test
        void handleResponseStatusException_shouldAnswer404_whenThePathIsUnknown() {
            expectProblem(server().get().uri("/internal/unknown").exchange(), 404, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        void handleResponseStatusException_shouldAnswer405_whenTheMethodIsWrong() {
            expectProblem(server().put().uri("/internal/media").exchange(), 405, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        void handleResponseStatusException_shouldAnswer415_whenTheContentTypeIsUnsupported() {
            expectProblem(server().post().uri("/internal/media/urls")
                    .contentType(MediaType.TEXT_PLAIN).bodyValue("keys").exchange(),
                    415, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        void handleResponseStatusException_shouldAnswer400_whenTheBodyIsUnreadable() {
            expectProblem(server().post().uri("/internal/media/urls")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{not json").exchange(),
                    400, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        void handleValidationException_shouldAnswer400_whenAFieldIsInvalid() {
            expectProblem(server().post().uri("/internal/media/urls")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"keys\":null}").exchange(),
                    400, GlobalErrorHandler.VALIDATION_FAILED_CODE);
        }
    }
}
