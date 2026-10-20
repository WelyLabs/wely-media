package com.calendar.media.configuration;

import com.calendar.media.domain.ports.ImageNormalizer;
import com.calendar.media.domain.ports.MediaActivity;
import com.calendar.media.domain.ports.ObjectStorage;
import com.calendar.media.domain.services.MediaService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the domain: MediaService stays a POJO, its adapters come in through the ports. */
@Configuration
@EnableConfigurationProperties(MediaClientsProperties.class)
public class MediaApplicationConfig {

    @Bean
    public MediaService mediaService(ObjectStorage storage, ImageNormalizer normalizer, MediaActivity activity) {
        return new MediaService(storage, normalizer, activity);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
