package com.calendar.media.infrastructure.observability;

import com.calendar.media.domain.ports.MediaActivity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link MediaActivity} as Micrometer meters. The names are a contract with the Grafana dashboards:
 * {@code wely_media_stored_total{client}} and {@code wely_media_stored_bytes_{count,sum,max}}.
 */
@Component
public class MicrometerMediaActivity implements MediaActivity {

    static final String STORED = "wely.media.stored";
    static final String STORED_BYTES = "wely.media.stored.bytes";
    static final String CLIENT_TAG = "client";

    private final MeterRegistry registry;
    private final DistributionSummary storedBytes;

    public MicrometerMediaActivity(MeterRegistry registry) {
        this.registry = registry;
        // Registered up front, so the series exists from the first scrape. No histogram: the
        // dashboards need the volume and the average size, and buckets would cost series on a Pi.
        this.storedBytes = DistributionSummary.builder(STORED_BYTES)
                .description("Size of the pictures stored, after normalization")
                .baseUnit("bytes")
                .register(registry);
    }

    @Override
    public void mediaStored(String client, long bytes) {
        Counter.builder(STORED)
                .description("Pictures stored, by calling service")
                .tag(CLIENT_TAG, client)
                .register(registry)
                .increment();
        storedBytes.record(bytes);
    }
}
