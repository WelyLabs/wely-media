package com.calendar.media.application.rest;

import com.calendar.media.application.dtos.StoredMediaResponse;
import com.calendar.media.application.dtos.UrlsRequest;
import com.calendar.media.application.dtos.UrlsResponse;
import com.calendar.media.application.mappers.MediaDtoMapper;
import com.calendar.media.domain.models.MediaClient;
import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.services.MediaService;
import com.calendar.media.exception.MediaErrorCode;
import com.calendar.media.exception.MediaException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;

/** Internal API for the business services, each one resolved from {@code X-Internal-Secret}. */
@RestController
@RequestMapping("/internal/media")
public class MediaController {

    /** Above this, refused before decoding. */
    static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final MediaService mediaService;
    private final MediaDtoMapper mapper;

    public MediaController(MediaService mediaService, MediaDtoMapper mapper) {
        this.mediaService = mediaService;
        this.mapper = mapper;
    }

    /** Multipart: {@code file}, {@code key}, {@code profile}. 413 above 12 MB, 400 if not a picture. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<StoredMediaResponse> store(@AuthenticationPrincipal MediaClient client,
                                           @RequestBody Mono<MultiValueMap<String, Part>> form) {
        return form.flatMap(parts -> {
                    if (!(parts.getFirst("file") instanceof FilePart file)
                            || !(parts.getFirst("key") instanceof FormFieldPart key)
                            || !(parts.getFirst("profile") instanceof FormFieldPart profile)) {
                        return Mono.error(invalid());
                    }
                    return parseProfile(profile.value())
                            .flatMap(parsed -> DataBufferUtils.join(file.content(), MAX_UPLOAD_BYTES)
                                    .map(buffer -> {
                                        byte[] bytes = new byte[buffer.readableByteCount()];
                                        buffer.read(bytes);
                                        DataBufferUtils.release(buffer);
                                        return bytes;
                                    })
                                    .switchIfEmpty(Mono.error(invalid()))
                                    .flatMap(bytes -> mediaService.store(client, key.value(), bytes, parsed)));
                })
                .onErrorMap(DataBufferLimitException.class, e -> new MediaException(MediaErrorCode.MEDIA_TOO_LARGE))
                .map(mapper::toResponse);
    }

    /** 204 even when the object was already gone. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal MediaClient client, @RequestParam String key) {
        return mediaService.delete(client, key);
    }

    /** Deletes every object under a folder of the caller's prefix; 204 even when nothing matched. */
    @DeleteMapping("/prefix")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deletePrefix(@AuthenticationPrincipal MediaClient client, @RequestParam String prefix) {
        return mediaService.deletePrefix(client, prefix)
                .doOnNext(count -> log.info("Prefix purge by {}: {} objects", client.name(), count))
                .then();
    }

    @PostMapping(path = "/urls", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UrlsResponse> urls(@AuthenticationPrincipal MediaClient client, @Valid @RequestBody UrlsRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        return mediaService.urls(client, request.keys(), ttl).map(UrlsResponse::new);
    }

    private static Mono<MediaProfile> parseProfile(String value) {
        try {
            return Mono.just(MediaProfile.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Mono.error(invalid());
        }
    }

    private static MediaException invalid() {
        return new MediaException(MediaErrorCode.MEDIA_INVALID);
    }
}
