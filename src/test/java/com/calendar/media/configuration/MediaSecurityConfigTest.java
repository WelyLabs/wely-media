package com.calendar.media.configuration;

import com.calendar.media.application.security.MediaClientAuthenticationManager;
import com.calendar.media.domain.models.MediaClient;
import com.calendar.media.domain.services.MediaService;
import com.calendar.media.exception.TechnicalErrorCode;
import com.calendar.media.exception.TechnicalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The secret filter end to end: who gets in, and what is open without a secret. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MediaSecurityConfigTest {

    private static final String DELETE_URI = "/internal/media?key=avatars/u1/a.jpg";

    @MockitoBean
    private MediaService mediaService;

    @Autowired
    private MediaClientAuthenticationManager authenticationManager;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private void expectClientUnknown(WebTestClient.ResponseSpec response) {
        response.expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-REQ-001")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/internal/media")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void internalHttpSecurity_shouldRefuseARequestWithoutSecret() {
        expectClientUnknown(client().delete().uri(DELETE_URI).exchange());

        verify(mediaService, never()).delete(any(), anyString());
    }

    @Test
    @DisplayName("a prefix purge without secret is refused before the service is reached")
    void internalHttpSecurity_shouldRefuseAPrefixPurgeWithoutSecret() {
        client().delete().uri("/internal/media/prefix?prefix=avatars/u1/")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-REQ-001")
                .jsonPath("$.instance").isEqualTo("/internal/media/prefix");

        verify(mediaService, never()).deletePrefix(any(), anyString());
    }

    @Test
    void internalHttpSecurity_shouldRefuseAWrongSecret() {
        expectClientUnknown(client().delete().uri(DELETE_URI).header("X-Internal-Secret", "guess").exchange());
    }

    @Test
    @DisplayName("a client configured without secret is disabled: no header value reaches it")
    void internalHttpSecurity_shouldNeverAdmitAClientWithABlankSecret() {
        expectClientUnknown(client().delete().uri(DELETE_URI).header("X-Internal-Secret", "").exchange());
    }

    @Test
    void internalHttpSecurity_shouldAdmitTheRightSecret_asItsClient() {
        MediaClient users = new MediaClient("users", "avatars/", "wely-avatars");
        when(mediaService.delete(users, "avatars/u1/a.jpg")).thenReturn(Mono.empty());

        client().delete().uri(DELETE_URI).header("X-Internal-Secret", "users-test-secret")
                .exchange()
                .expectStatus().isNoContent();

        verify(mediaService).delete(users, "avatars/u1/a.jpg");
    }

    @Test
    @DisplayName("the kubelet's probes need no secret, the rest of the actuator does")
    void internalHttpSecurity_shouldOpenOnlyTheHealthGroups() {
        client().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client().get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
        client().get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the metrics scrape needs no secret and carries the technical error counter")
    void internalHttpSecurity_shouldOpenThePrometheusScrape() {
        when(mediaService.delete(any(), anyString()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)));
        client().delete().uri(DELETE_URI).header("X-Internal-Secret", "users-test-secret")
                .exchange()
                .expectStatus().isEqualTo(502);

        client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("# TYPE wely_errors_technical_total counter")
                        .contains("wely_errors_technical_total{code=\"MED-TEC-001\"}"));
    }

    @Test
    @DisplayName("opening the scrape opens nothing else: other actuator paths stay closed or unexposed")
    void internalHttpSecurity_shouldKeepTheRestOfTheActuatorClosed() {
        client().get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
        client().post().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        // Even with a valid secret, the endpoint is not exposed at all.
        client().get().uri("/actuator/env").header("X-Internal-Secret", "users-test-secret")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void mediaClientAuthenticationManager_shouldBeBuiltFromTheConfiguredClients() {
        assertThat(authenticationManager.resolve("users-test-secret"))
                .contains(new MediaClient("users", "avatars/", "wely-avatars"));
        assertThat(authenticationManager.resolve("chat-test-secret"))
                .contains(new MediaClient("chat", "chat/", "wely-chat-media"));
        assertThat(authenticationManager.resolve("")).isEmpty();
    }
}
