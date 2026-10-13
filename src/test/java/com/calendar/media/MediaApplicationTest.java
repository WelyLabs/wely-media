package com.calendar.media;

import com.calendar.media.domain.ports.ImageNormalizer;
import com.calendar.media.domain.ports.ObjectStorage;
import com.calendar.media.domain.services.MediaService;
import com.calendar.media.infrastructure.imaging.adapters.ImageIoImageNormalizerAdapter;
import com.calendar.media.infrastructure.storage.adapters.GarageObjectStorageAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** The context starts without Garage, and the ports resolve to their adapters. */
@SpringBootTest
@ActiveProfiles("test")
class MediaApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextShouldWireTheDomainServiceAndItsAdapters() {
        assertThat(context.getBean(MediaService.class)).isNotNull();
        assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(GarageObjectStorageAdapter.class);
        assertThat(context.getBean(ImageNormalizer.class)).isInstanceOf(ImageIoImageNormalizerAdapter.class);
        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(Clock.systemUTC().getZone());
    }

    @Test
    void main_shouldStartTheApplication() {
        MediaApplication.main(new String[] {"--spring.profiles.active=test", "--spring.main.web-application-type=none"});
    }
}
