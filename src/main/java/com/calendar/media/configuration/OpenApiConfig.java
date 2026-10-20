package com.calendar.media.configuration;

import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.GlobalErrorHandler;
import com.calendar.media.exception.TechnicalErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Names the API that springdoc generates from the controllers, and documents its error shape. */
@Configuration
public class OpenApiConfig {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private static final String CODE_DESCRIPTION = """
            Stable identifier a caller branches on. Families: BUS a business rule refused the request, \
            change the request; VAL one or more fields are invalid, see `errors`; REQ the request was \
            refused before reaching the business logic (caller unknown, unknown path, wrong method, \
            unreadable body); TEC a dependency failed, retry later — this is an incident.""";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("wely-media")
                .version("v1")
                .description("Internal picture storage. Callers authenticate with X-Internal-Secret; "
                        + "each is confined to its own bucket and key prefix."));
    }

    /**
     * Registers the RFC 7807 {@code Problem} schema and declares it as the {@code 4XX} and {@code 5XX}
     * answer of every operation, so a client can generate one error type instead of reading the code.
     */
    @Bean
    public OpenApiCustomizer problemResponsesCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(OpenApiConfig::addProblemResponses));
        };
    }

    /** Every code this service can answer, read from the enums and the handler so the list cannot drift. */
    static List<String> allErrorCodes() {
        return Stream.of(
                        Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode),
                        Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode),
                        Stream.of(GlobalErrorHandler.VALIDATION_CODE, GlobalErrorHandler.REQUEST_CODE,
                                GlobalErrorHandler.UNEXPECTED_CODE))
                .flatMap(codes -> codes)
                .sorted()
                .toList();
    }

    private static Schema<?> problemSchema() {
        StringSchema code = new StringSchema();
        code.setDescription(CODE_DESCRIPTION);
        code.setEnum(allErrorCodes());

        return new ObjectSchema()
                .description("RFC 7807 problem details, as answered by every failure of this service.")
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", code)
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("requestId", new StringSchema()
                        .description("Request id, also printed on the server's log lines for this request."))
                .addProperty("errors", new MapSchema()
                        .additionalProperties(new StringSchema())
                        .description("Offending fields and their message; only for " + GlobalErrorHandler.VALIDATION_CODE + "."));
    }

    private static void addProblemResponses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();
        if (!responses.containsKey("4XX")) {
            responses.addApiResponse("4XX", problemResponse("The request was refused; see `code`."));
        }
        if (!responses.containsKey("5XX")) {
            responses.addApiResponse("5XX", problemResponse("This service or one of its dependencies failed; see `code`."));
        }
    }

    private static ApiResponse problemResponse(String description) {
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(reference)));
    }
}
