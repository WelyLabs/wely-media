package com.calendar.media.configuration;

import com.calendar.media.domain.services.MediaService;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// A running server: springdoc registers its routes on the web handler, which a context-bound client misses.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiConfigTest {

    // Same mock as the other web tests, so the three share one context.
    @MockitoBean
    private MediaService mediaService;

    @Autowired
    private ApplicationContext context;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void openApi_shouldDescribeTheService() {
        var info = context.getBean(OpenAPI.class).getInfo();

        assertThat(info.getTitle()).isEqualTo("wely-media");
        assertThat(info.getVersion()).isEqualTo("v1");
        assertThat(info.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("the specification answers without a secret and documents the unprefixed internal paths")
    void apiDocs_shouldBeReachableWithoutSecret() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("wely-media")
                .jsonPath("$.paths['/internal/media']").exists()
                .jsonPath("$.paths['/internal/media/urls']").exists();
    }

    @Test
    void swaggerUi_shouldBeReachableWithoutSecret() {
        client().get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk();
    }
}
