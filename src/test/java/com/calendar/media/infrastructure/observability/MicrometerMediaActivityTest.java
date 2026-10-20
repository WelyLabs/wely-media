package com.calendar.media.infrastructure.observability;

import com.calendar.media.domain.ports.MediaActivity;
import com.calendar.media.domain.services.MediaService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerMediaActivityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerMediaActivity activity = new MicrometerMediaActivity(registry);

    @Test
    void mediaStored_shouldCountByClient_andRecordTheSize() {
        activity.mediaStored("chat", 2_000);
        activity.mediaStored("chat", 4_000);
        activity.mediaStored("users", 1_000);

        assertThat(registry.get("wely.media.stored").tag("client", "chat").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("wely.media.stored").tag("client", "users").counter().count()).isEqualTo(1.0);
        DistributionSummary bytes = registry.get("wely.media.stored.bytes").summary();
        assertThat(bytes.count()).isEqualTo(3);
        assertThat(bytes.totalAmount()).isEqualTo(7_000.0);
        assertThat(bytes.getId().getBaseUnit()).isEqualTo("bytes");
        assertThat(bytes.getId().getTags()).isEmpty();
    }

    @Test
    @DisplayName("the size summary exists before anything is stored, so the first scrape shows it")
    void constructor_shouldRegisterTheSizeSummaryUpFront() {
        assertThat(registry.get("wely.media.stored.bytes").summary().count()).isZero();
        assertThat(registry.find("wely.media.stored").counters()).isEmpty();
    }

    /** The adapter as wired in the running application, read through the metrics scrape. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    class InTheRunningApplication {

        // Same mock as the other web tests, so they all share one context.
        @MockitoBean
        private MediaService mediaService;

        @Autowired
        private MediaActivity wiredActivity;

        @Value("${local.server.port}")
        private int port;

        @Test
        void mediaStored_shouldBeScrapedUnderTheContractNames() {
            wiredActivity.mediaStored("events", 3_000);

            WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .build()
                    .get().uri("/actuator/prometheus")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body)
                            .contains("wely_media_stored_total{client=\"events\"}")
                            .contains("wely_media_stored_bytes_count")
                            .contains("wely_media_stored_bytes_sum"));
        }
    }
}
