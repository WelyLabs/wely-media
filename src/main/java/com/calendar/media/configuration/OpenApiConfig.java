package com.calendar.media.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Names the API that springdoc generates from the controllers. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("wely-media")
                .version("v1")
                .description("Internal picture storage. Callers authenticate with X-Internal-Secret; "
                        + "each is confined to its own bucket and key prefix."));
    }
}
