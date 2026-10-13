package com.calendar.media.domain.ports;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Where the bytes live. The bucket is passed on every call: each client has its own. */
public interface ObjectStorage {

    Mono<Void> put(String bucket, String key, byte[] content, String contentType);

    /** Deleting a missing object is not an error. */
    Mono<Void> delete(String bucket, String key);

    /** A URL a browser can open until {@code ttl} runs out. */
    String presignGet(String bucket, String key, Duration ttl);
}
