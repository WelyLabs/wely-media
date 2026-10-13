package com.calendar.media.configuration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StorageClientConfigTest {

    private final StorageClientConfig config = new StorageClientConfig();

    @Test
    void storageWebClient_shouldBeBuilt() {
        assertThat(config.storageWebClient()).isNotNull();
    }

    @Test
    void garageRequestSigner_shouldSignWithTheConfiguredKeyAndRegion() {
        URI url = config.garageRequestSigner("GK1", "secret", "garage", Clock.systemUTC())
                .presign("GET", URI.create("http://localhost:3900"), "/b/k", Duration.ofMinutes(1), Map.of());

        assertThat(url.getRawQuery()).contains("X-Amz-Credential=GK1%2F").contains("%2Fgarage%2Fs3%2F");
    }
}
