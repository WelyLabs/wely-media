package com.calendar.media.domain.ports;

import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.models.NormalizedImage;
import reactor.core.publisher.Mono;

/** Decodes an upload and re-encodes it to the profile's shape, or refuses it. */
public interface ImageNormalizer {

    Mono<NormalizedImage> normalize(byte[] upload, MediaProfile profile);
}
