package com.calendar.media.infrastructure.storage.adapters;

import com.calendar.media.domain.ports.ObjectStorage;
import com.calendar.media.exception.BusinessException;
import com.calendar.media.exception.TechnicalErrorCode;
import com.calendar.media.exception.TechnicalException;
import com.calendar.media.infrastructure.storage.listing.ListBucketPage;
import com.calendar.media.infrastructure.storage.listing.ListBucketResultParser;
import com.calendar.media.infrastructure.storage.signing.GarageRequestSigner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Private Garage buckets through signed S3 calls; any failure reaching Garage becomes STORAGE_UNAVAILABLE. */
@Component
public class GarageObjectStorageAdapter implements ObjectStorage {

    static final String CACHE_CONTROL = "private, max-age=3600";
    /** An internal request is signed just before it is sent, so a minute is ample. */
    static final Duration WRITE_TTL = Duration.ofMinutes(1);

    private final WebClient client;
    private final GarageRequestSigner signer;
    private final URI internalEndpoint;
    private final URI publicEndpoint;
    private final int listPageSize;

    public GarageObjectStorageAdapter(@Qualifier("storageWebClient") WebClient client,
                                      GarageRequestSigner signer,
                                      @Value("${app.storage.internal-endpoint}") URI internalEndpoint,
                                      @Value("${app.storage.public-endpoint}") URI publicEndpoint,
                                      @Value("${app.storage.list-page-size:200}") int listPageSize) {
        this.client = client;
        this.signer = signer;
        this.internalEndpoint = internalEndpoint;
        this.publicEndpoint = publicEndpoint;
        this.listPageSize = listPageSize;
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
                .onErrorMap(GarageObjectStorageAdapter::unexpected, GarageObjectStorageAdapter::unavailable);
    }

    /** Garage answers 204 for a missing key too, so deleting twice is not an error. */
    @Override
    public Mono<Void> delete(String bucket, String key) {
        return Mono.defer(() -> client.delete()
                        .uri(signer.presign(HttpMethod.DELETE.name(), internalEndpoint, path(bucket, key), WRITE_TTL, Map.of()))
                        .retrieve()
                        .toBodilessEntity()
                        .then())
                .onErrorMap(GarageObjectStorageAdapter::unexpected, GarageObjectStorageAdapter::unavailable);
    }

    /** ListObjectsV2, one signed request per page, following the continuation token. */
    @Override
    public Flux<String> list(String bucket, String prefix) {
        return listPage(bucket, prefix, null)
                .expand(page -> page.nextToken() == null ? Mono.empty() : listPage(bucket, prefix, page.nextToken()))
                .concatMapIterable(ListBucketPage::keys);
    }

    @Override
    public String presignGet(String bucket, String key, Duration ttl) {
        return signer.presign(HttpMethod.GET.name(), publicEndpoint, path(bucket, key), ttl,
                        Map.of("response-cache-control", CACHE_CONTROL))
                .toString();
    }

    // A token handed back unchanged would loop forever: fail rather than report a partial listing as complete.
    private Mono<ListBucketPage> listPage(String bucket, String prefix, String token) {
        return Mono.defer(() -> {
                    Map<String, String> query = new LinkedHashMap<>();
                    query.put("list-type", "2");
                    query.put("prefix", prefix);
                    query.put("max-keys", Integer.toString(listPageSize));
                    if (token != null) {
                        query.put("continuation-token", token);
                    }
                    return client.get()
                            .uri(signer.presign(HttpMethod.GET.name(), internalEndpoint, "/" + bucket, WRITE_TTL, query))
                            .retrieve()
                            .bodyToMono(byte[].class)
                            .switchIfEmpty(Mono.error(() -> new IllegalStateException("Empty bucket listing")))
                            .map(ListBucketResultParser::parse);
                })
                .flatMap(page -> token != null && Objects.equals(page.nextToken(), token)
                        ? Mono.<ListBucketPage>error(new IllegalStateException("Bucket listing repeated its continuation token"))
                        : Mono.just(page))
                .onErrorMap(GarageObjectStorageAdapter::unexpected, GarageObjectStorageAdapter::unavailable);
    }

    /** Path-style addressing: there is no wildcard hostname per bucket. */
    private static String path(String bucket, String key) {
        return "/" + bucket + "/" + key;
    }

    /** An error that already carries a code, business or technical, goes through unchanged. */
    private static boolean unexpected(Throwable error) {
        return !(error instanceof BusinessException || error instanceof TechnicalException);
    }

    private static TechnicalException unavailable(Throwable cause) {
        return new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE, cause);
    }
}
