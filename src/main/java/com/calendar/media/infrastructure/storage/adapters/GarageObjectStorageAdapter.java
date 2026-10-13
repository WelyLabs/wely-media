package com.calendar.media.infrastructure.storage.adapters;

import com.calendar.media.domain.ports.ObjectStorage;
import com.calendar.media.exception.MediaErrorCode;
import com.calendar.media.exception.MediaException;
import com.calendar.media.infrastructure.storage.signing.GarageRequestSigner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Stores pictures in private Garage buckets through its S3 API, with plain HTTP calls signed by
 * {@link GarageRequestSigner}. Any failure reaching Garage becomes STORAGE_UNAVAILABLE (502).
 *
 * <p>Two endpoints, on purpose. Writes go to the internal Service. Read URLs are signed for the
 * public hostname, because a signature covers the host: one signed for the internal name would be
 * refused when the browser presents it through the tunnel.
 *
 * <p>Every object, and every signed read, says {@code Cache-Control: private}: the hostname sits
 * behind Cloudflare, and a shared cache must never hand a private picture to someone else.
 */
@Component
public class GarageObjectStorageAdapter implements ObjectStorage {

    static final String CACHE_CONTROL = "private, max-age=3600";
    /** A write is signed just before it is sent: a minute is ample and leaves nothing reusable. */
    static final Duration WRITE_TTL = Duration.ofMinutes(1);

    private final WebClient client;
    private final GarageRequestSigner signer;
    private final URI internalEndpoint;
    private final URI publicEndpoint;

    public GarageObjectStorageAdapter(@Qualifier("storageWebClient") WebClient client,
                                      GarageRequestSigner signer,
                                      @Value("${app.storage.internal-endpoint}") URI internalEndpoint,
                                      @Value("${app.storage.public-endpoint}") URI publicEndpoint) {
        this.client = client;
        this.signer = signer;
        this.internalEndpoint = internalEndpoint;
        this.publicEndpoint = publicEndpoint;
    }

    @Override
    public Mono<Void> put(String bucket, String key, byte[] content, String contentType) {
        return Mono.defer(() -> client.put()
                        .uri(signer.presign(HttpMethod.PUT.name(), internalEndpoint, path(bucket, key), WRITE_TTL, Map.of()))
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                        .bodyValue(content)
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .onErrorMap(GarageObjectStorageAdapter::unexpected, error -> unavailable());
    }

    /** Garage answers 204 for a missing key too, so deleting twice is not an error. */
    @Override
    public Mono<Void> delete(String bucket, String key) {
        return Mono.defer(() -> client.delete()
                        .uri(signer.presign(HttpMethod.DELETE.name(), internalEndpoint, path(bucket, key), WRITE_TTL, Map.of()))
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .onErrorMap(GarageObjectStorageAdapter::unexpected, error -> unavailable());
    }

    @Override
    public String presignGet(String bucket, String key, Duration ttl) {
        return signer.presign(HttpMethod.GET.name(), publicEndpoint, path(bucket, key), ttl,
                        Map.of("response-cache-control", CACHE_CONTROL))
                .toString();
    }

    /** Path-style addressing: there is no wildcard hostname per bucket. */
    private static String path(String bucket, String key) {
        return "/" + bucket + "/" + key;
    }

    private static boolean unexpected(Throwable error) {
        return !(error instanceof MediaException);
    }

    private static MediaException unavailable() {
        return new MediaException(MediaErrorCode.STORAGE_UNAVAILABLE);
    }
}
