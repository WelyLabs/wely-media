package com.calendar.media.configuration;

import com.calendar.media.infrastructure.storage.signing.GarageRequestSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/** The object storage, Garage in the cluster: an HTTP client and the signer for its S3 API. */
@Configuration
public class StorageClientConfig {

    /** No base URL: every request carries its own signed, absolute URI. */
    @Bean
    public WebClient storageWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    public GarageRequestSigner garageRequestSigner(@Value("${app.storage.access-key}") String accessKey,
                                                   @Value("${app.storage.secret-key}") String secretKey,
                                                   @Value("${app.storage.region}") String region,
                                                   Clock clock) {
        return new GarageRequestSigner(accessKey, secretKey, region, clock);
    }
}
