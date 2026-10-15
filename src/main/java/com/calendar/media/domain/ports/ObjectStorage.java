package com.calendar.media.domain.ports;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Where the bytes live; every call names its bucket, since each client has its own. */
public interface ObjectStorage {

    Mono<Void> put(String bucket, String key, byte[] content, String contentType);

    /** Deleting a missing object is not an error. */
    Mono<Void> delete(String bucket, String key);

    /** Every key that starts with the prefix, page after page; none is not an error. */
    Flux<String> list(String bucket, String prefix);

    /** A URL a browser can open until {@code ttl} runs out. */
    String presignGet(String bucket, String key, Duration ttl);
}
