package com.calendar.media.domain.services;

import com.calendar.media.domain.models.MediaClient;
import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.models.NormalizedImage;
import com.calendar.media.domain.models.StoredMedia;
import com.calendar.media.domain.ports.ImageNormalizer;
import com.calendar.media.domain.ports.ObjectStorage;
import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.BusinessException;
import com.calendar.media.exception.TechnicalErrorCode;
import com.calendar.media.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    private static final MediaClient CHAT = new MediaClient("chat", "chat/", "wely-chat-media");
    private static final MediaClient USERS = new MediaClient("users", "avatars/", "wely-avatars");
    private static final byte[] UPLOAD = {1, 2, 3};

    @Mock private ObjectStorage storage;
    @Mock private ImageNormalizer normalizer;

    private MediaService service;

    @BeforeEach
    void setUp() {
        service = new MediaService(storage, normalizer);
    }

    private static boolean failsWith(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException business && business.getErrorCode() == code;
    }

    private static boolean failsWith(Throwable error, TechnicalErrorCode code) {
        return error instanceof TechnicalException technical && technical.getErrorCode() == code;
    }

    @Test
    void store_shouldNormalizeThenWriteInTheClientsBucket() {
        byte[] jpeg = {9};
        when(normalizer.normalize(UPLOAD, MediaProfile.PHOTO))
                .thenReturn(Mono.just(new NormalizedImage(jpeg, "image/jpeg", 1600, 1200)));
        when(storage.put("wely-chat-media", "chat/c1/m1.jpg", jpeg, "image/jpeg")).thenReturn(Mono.empty());

        StepVerifier.create(service.store(CHAT, "chat/c1/m1.jpg", UPLOAD, MediaProfile.PHOTO))
                .expectNext(new StoredMedia("chat/c1/m1.jpg", 1600, 1200, "image/jpeg"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a key outside the caller's prefix is refused before the upload is decoded")
    void store_shouldRefuseAForeignKey_withoutDecoding() {
        StepVerifier.create(service.store(CHAT, "avatars/u1/a.jpg", UPLOAD, MediaProfile.AVATAR))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_KEY_FORBIDDEN))
                .verify();

        verifyNoInteractions(normalizer, storage);
    }

    @Test
    void store_shouldDoNothingUntilSubscribed() {
        service.store(CHAT, "chat/c1/m1.jpg", UPLOAD, MediaProfile.PHOTO);

        verifyNoInteractions(normalizer, storage);
    }

    @Test
    void store_shouldNotWrite_whenTheUploadIsRefused() {
        when(normalizer.normalize(UPLOAD, MediaProfile.PHOTO))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.MEDIA_INVALID)));

        StepVerifier.create(service.store(CHAT, "chat/c1/m1.jpg", UPLOAD, MediaProfile.PHOTO))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_INVALID))
                .verify();

        verify(storage, never()).put(anyString(), anyString(), any(), anyString());
    }

    @Test
    void store_shouldFail_whenTheStorageIsDown() {
        when(normalizer.normalize(UPLOAD, MediaProfile.PHOTO))
                .thenReturn(Mono.just(new NormalizedImage(new byte[] {9}, "image/jpeg", 10, 10)));
        when(storage.put(anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)));

        StepVerifier.create(service.store(CHAT, "chat/c1/m1.jpg", UPLOAD, MediaProfile.PHOTO))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.STORAGE_UNAVAILABLE))
                .verify();
    }

    @Test
    void delete_shouldRemoveFromTheClientsBucket() {
        when(storage.delete("wely-chat-media", "chat/c1/m1.jpg")).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(CHAT, "chat/c1/m1.jpg")).verifyComplete();

        verify(storage).delete("wely-chat-media", "chat/c1/m1.jpg");
    }

    @Test
    void delete_shouldRefuseAForeignKey() {
        StepVerifier.create(service.delete(CHAT, "events/e1/cover.jpg"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_KEY_FORBIDDEN))
                .verify();

        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("every key listed under the prefix is deleted from the client's bucket, and counted")
    void deletePrefix_shouldDeleteEveryListedKeyInTheClientsBucket() {
        when(storage.list("wely-avatars", "avatars/u1/"))
                .thenReturn(Flux.just("avatars/u1/a.jpg", "avatars/u1/b.jpg", "avatars/u1/old/c.jpg"));
        when(storage.delete(eq("wely-avatars"), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.deletePrefix(USERS, "avatars/u1/")).expectNext(3L).verifyComplete();

        verify(storage).delete("wely-avatars", "avatars/u1/a.jpg");
        verify(storage).delete("wely-avatars", "avatars/u1/b.jpg");
        verify(storage).delete("wely-avatars", "avatars/u1/old/c.jpg");
    }

    @Test
    @DisplayName("an empty folder counts nothing and deletes nothing")
    void deletePrefix_shouldCountNothing_whenNothingIsUnderThePrefix() {
        when(storage.list("wely-avatars", "avatars/u1/")).thenReturn(Flux.empty());

        StepVerifier.create(service.deletePrefix(USERS, "avatars/u1/")).expectNext(0L).verifyComplete();

        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("a prefix outside the caller's own is refused before anything is listed")
    void deletePrefix_shouldRefuseAForeignPrefix_withoutListing() {
        StepVerifier.create(service.deletePrefix(USERS, "chat/c1/"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_KEY_FORBIDDEN))
                .verify();
        StepVerifier.create(service.deletePrefix(USERS, "avatars/"))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_KEY_FORBIDDEN))
                .verify();

        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("whatever the listing returns, a key outside the prefix is never deleted")
    void deletePrefix_shouldNeverDeleteAListedKeyOutsideThePrefix() {
        when(storage.list("wely-avatars", "avatars/u1/"))
                .thenReturn(Flux.just("avatars/u1/a.jpg", "avatars/u10/a.jpg", "chat/c1/m1.jpg"));
        when(storage.delete("wely-avatars", "avatars/u1/a.jpg")).thenReturn(Mono.empty());

        StepVerifier.create(service.deletePrefix(USERS, "avatars/u1/")).expectNext(1L).verifyComplete();

        verify(storage, never()).delete("wely-avatars", "avatars/u10/a.jpg");
        verify(storage, never()).delete("wely-avatars", "chat/c1/m1.jpg");
    }

    @Test
    void deletePrefix_shouldFail_whenListingFails() {
        when(storage.list("wely-avatars", "avatars/u1/"))
                .thenReturn(Flux.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)));

        StepVerifier.create(service.deletePrefix(USERS, "avatars/u1/"))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.STORAGE_UNAVAILABLE))
                .verify();

        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void deletePrefix_shouldFail_whenADeleteFails() {
        when(storage.list("wely-avatars", "avatars/u1/")).thenReturn(Flux.just("avatars/u1/a.jpg"));
        when(storage.delete("wely-avatars", "avatars/u1/a.jpg"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)));

        StepVerifier.create(service.deletePrefix(USERS, "avatars/u1/"))
                .expectErrorMatches(error -> failsWith(error, TechnicalErrorCode.STORAGE_UNAVAILABLE))
                .verify();
    }

    @Test
    void deletePrefix_shouldDoNothingUntilSubscribed() {
        service.deletePrefix(USERS, "avatars/u1/");

        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("each distinct key is signed once, in the order asked")
    void urls_shouldSignEachDistinctKeyOnce() {
        when(storage.presignGet(eq("wely-chat-media"), anyString(), eq(Duration.ofMinutes(10))))
                .thenAnswer(call -> "https://media/" + call.getArgument(1));

        StepVerifier.create(service.urls(CHAT, List.of("chat/b.jpg", "chat/a.jpg", "chat/b.jpg"), Duration.ofMinutes(10)))
                .assertNext(urls -> assertThat(urls).containsExactly(
                        org.assertj.core.api.Assertions.entry("chat/b.jpg", "https://media/chat/b.jpg"),
                        org.assertj.core.api.Assertions.entry("chat/a.jpg", "https://media/chat/a.jpg")))
                .verifyComplete();

        verify(storage).presignGet("wely-chat-media", "chat/b.jpg", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("one key outside the prefix refuses the whole batch, and nothing is signed")
    void urls_shouldRefuseTheBatch_whenOneKeyIsForeign() {
        StepVerifier.create(service.urls(CHAT, List.of("chat/a.jpg", "avatars/u1/a.jpg"), Duration.ofHours(1)))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_KEY_FORBIDDEN))
                .verify();

        verifyNoInteractions(storage);
    }

    @Test
    void urls_shouldRefuseMoreThan200DistinctKeys() {
        List<String> keys = IntStream.rangeClosed(0, MediaService.MAX_KEYS).mapToObj(i -> "chat/" + i + ".jpg").toList();

        StepVerifier.create(service.urls(CHAT, keys, Duration.ofHours(1)))
                .expectErrorMatches(error -> failsWith(error, BusinessErrorCode.MEDIA_INVALID))
                .verify();
    }

    @Test
    @DisplayName("duplicates count once against the limit")
    void urls_shouldAcceptOverLimitInput_whenDuplicatesCollapseUnderIt() {
        List<String> keys = IntStream.range(0, 300).mapToObj(i -> "chat/" + (i % 2) + ".jpg").toList();
        when(storage.presignGet(anyString(), anyString(), any())).thenReturn("u");

        StepVerifier.create(service.urls(CHAT, keys, Duration.ofHours(1)))
                .assertNext(urls -> assertThat(urls).hasSize(2))
                .verifyComplete();
    }

    @Test
    void urls_shouldAnswerEmpty_whenNoKeyIsAsked() {
        StepVerifier.create(service.urls(CHAT, List.of(), Duration.ofHours(1)))
                .assertNext(urls -> assertThat(urls).isEmpty())
                .verifyComplete();
    }

    @Test
    void urls_shouldClampTheTtl() {
        when(storage.presignGet(anyString(), anyString(), any())).thenReturn("u");

        StepVerifier.create(service.urls(CHAT, List.of("chat/a.jpg"), Duration.ofDays(7))).expectNextCount(1).verifyComplete();

        verify(storage).presignGet("wely-chat-media", "chat/a.jpg", MediaService.MAX_TTL);
    }

    @Test
    void clamp_shouldKeepTheTtlWithinBounds() {
        assertThat(MediaService.clamp(Duration.ofSeconds(5))).isEqualTo(MediaService.MIN_TTL);
        assertThat(MediaService.clamp(Duration.ofDays(1))).isEqualTo(MediaService.MAX_TTL);
        assertThat(MediaService.clamp(Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(30));
        assertThat(MediaService.clamp(null)).isEqualTo(MediaService.DEFAULT_TTL);
    }
}
