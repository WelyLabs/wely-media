package com.calendar.media.configuration;

import com.calendar.media.domain.services.MediaService;
import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.GlobalErrorHandler;
import com.calendar.media.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

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
        // The first /v3/api-docs call builds the whole specification: on a loaded machine it outlasts the 5 s default.
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
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
    @DisplayName("the published specification carries the Problem schema and the problem responses")
    void apiDocs_shouldDocumentTheProblemResponses() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.schemas.Problem.properties.requestId").exists()
                .jsonPath("$.components.schemas.Problem.properties.code.type").isEqualTo("string")
                .jsonPath("$.components.schemas.Problem.properties.code.enum").isArray()
                .jsonPath("$.paths['/internal/media'].delete.responses['4XX'].content['application/problem+json'].schema['$ref']")
                .isEqualTo("#/components/schemas/Problem")
                .jsonPath("$.paths['/internal/media'].delete.responses['5XX']").exists();
    }

    private static OpenAPI customized(Operation operation) {
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/x", new PathItem().get(operation)));
        new OpenApiConfig().problemResponsesCustomizer().customise(openApi);
        return openApi;
    }

    @Test
    void problemResponsesCustomizer_shouldRegisterTheProblemSchema_withEveryErrorCode() {
        Schema<?> problem = customized(new Operation()).getComponents().getSchemas().get(OpenApiConfig.PROBLEM_SCHEMA);

        assertThat(problem.getProperties()).containsKeys("type", "title", "status", "detail", "instance", "code",
                "timestamp", "requestId", "errors");
        Schema<?> code = problem.getProperties().get("code");
        assertThat(code.getDescription()).contains("BUS", "VAL", "REQ", "TEC");
        assertThat(code.getEnum()).map(String::valueOf).contains(
                BusinessErrorCode.MEDIA_KEY_FORBIDDEN.getCode(),
                TechnicalErrorCode.STORAGE_UNAVAILABLE.getCode(),
                GlobalErrorHandler.UNEXPECTED_FAILURE_CODE);
    }

    @Test
    void problemResponsesCustomizer_shouldAdd4xxAnd5xxProblemResponses_toEveryOperation() {
        ApiResponses responses = customized(new Operation()).getPaths().get("/x").getGet().getResponses();

        assertThat(responses).containsKeys("4XX", "5XX");
        assertThat(responses.get("5XX").getContent().get(OpenApiConfig.PROBLEM_JSON).getSchema().get$ref())
                .isEqualTo("#/components/schemas/Problem");
    }

    @Test
    @DisplayName("a response an operation already declares is left as it is")
    void problemResponsesCustomizer_shouldKeepAnAlreadyDeclaredResponse() {
        ApiResponse declared = new ApiResponse().description("declared by the controller");
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("4XX", declared));

        ApiResponses responses = customized(operation).getPaths().get("/x").getGet().getResponses();

        assertThat(responses.get("4XX")).isSameAs(declared);
        assertThat(responses).containsKey("5XX");
    }

    @Test
    void allErrorCodes_shouldListEveryEnumCodeAndTheHandlerCodes() {
        assertThat(OpenApiConfig.allErrorCodes())
                .hasSize(BusinessErrorCode.values().length + TechnicalErrorCode.values().length
                        + GlobalErrorHandler.HANDLER_CODES.size())
                .containsAll(GlobalErrorHandler.HANDLER_CODES)
                .doesNotHaveDuplicates();
    }

    @Test
    void swaggerUi_shouldBeReachableWithoutSecret() {
        client().get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk();
    }
}
