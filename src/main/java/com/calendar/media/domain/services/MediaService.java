package com.calendar.media.domain.services;

import com.calendar.media.domain.models.MediaClient;
import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.models.StoredMedia;
import com.calendar.media.domain.ports.ImageNormalizer;
import com.calendar.media.domain.ports.ObjectStorage;
import com.calendar.media.exception.MediaErrorCode;
import com.calendar.media.exception.MediaException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stores, removes and signs pictures for a calling service. Access rules stay in the caller;
 * this service only confines each caller to its own bucket and key prefix.
 */
public class MediaService {

    static final Duration DEFAULT_TTL = Duration.ofHours(1);
    static final Duration MIN_TTL = Duration.ofMinutes(1);
    static final Duration MAX_TTL = Duration.ofHours(12);
    static final int MAX_KEYS = 200;

    private final ObjectStorage storage;
    private final ImageNormalizer normalizer;

    public MediaService(ObjectStorage storage, ImageNormalizer normalizer) {
        this.storage = storage;
        this.normalizer = normalizer;
    }

    /** The key is checked before the upload is decoded: a forbidden key costs nothing. */
    public Mono<StoredMedia> store(MediaClient client, String key, byte[] bytes, MediaProfile profile) {
        return Mono.defer(() -> {
            if (!client.owns(key)) {
                return Mono.error(forbidden());
            }
            return normalizer.normalize(bytes, profile)
                    .flatMap(image -> storage.put(client.bucket(), key, image.content(), image.contentType())
                            .thenReturn(new StoredMedia(key, image.width(), image.height(), image.contentType())));
        });
    }

    public Mono<Void> delete(MediaClient client, String key) {
        return Mono.defer(() -> client.owns(key)
                ? storage.delete(client.bucket(), key)
                : Mono.error(forbidden()));
    }

    /** Signed read URLs by key; one foreign key refuses the whole batch. */
    public Mono<Map<String, String>> urls(MediaClient client, Collection<String> keys, Duration ttl) {
        return Mono.fromCallable(() -> {
            Set<String> unique = new LinkedHashSet<>(keys);
            if (unique.size() > MAX_KEYS) {
                throw new MediaException(MediaErrorCode.MEDIA_INVALID);
            }
            if (!unique.stream().allMatch(client::owns)) {
                throw forbidden();
            }
            Duration clamped = clamp(ttl);
            Map<String, String> urls = new LinkedHashMap<>();
            unique.forEach(key -> urls.put(key, storage.presignGet(client.bucket(), key, clamped)));
            return urls;
        });
    }

    static Duration clamp(Duration ttl) {
        if (ttl == null) {
            return DEFAULT_TTL;
        }
        if (ttl.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }

    private static MediaException forbidden() {
        return new MediaException(MediaErrorCode.MEDIA_KEY_FORBIDDEN);
    }
}
