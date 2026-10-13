package com.calendar.media.configuration;

import com.calendar.media.domain.ports.ImageNormalizer;
import com.calendar.media.domain.ports.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaApplicationConfigTest {

    private final MediaApplicationConfig config = new MediaApplicationConfig();

    @Test
    void mediaService_shouldBeBuiltWithoutSpring() {
        assertThat(config.mediaService(mock(ObjectStorage.class), mock(ImageNormalizer.class))).isNotNull();
    }

    @Test
    void clock_shouldTickInUtc() {
        assertThat(config.clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
