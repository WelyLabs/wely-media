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
    void sigV4RequestSigner_shouldSignWithTheConfiguredKeyAndRegion() {
        URI url = config.sigV4RequestSigner("GK1", "secret", "auto", Clock.systemUTC())
                .presign("GET", URI.create("http://localhost:3900"), "/b/k", Duration.ofMinutes(1), Map.of());

        assertThat(url.getRawQuery()).contains("X-Amz-Credential=GK1%2F").contains("%2Fauto%2Fs3%2F");
    }
}
