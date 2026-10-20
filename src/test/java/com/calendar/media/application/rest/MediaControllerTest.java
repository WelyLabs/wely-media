package com.calendar.media.application.rest;

import com.calendar.media.domain.models.MediaClient;
import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.models.StoredMedia;
import com.calendar.media.domain.services.MediaService;
import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.BusinessException;
import com.calendar.media.exception.TechnicalErrorCode;
import com.calendar.media.exception.TechnicalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Through a running server: the multipart codec, the security chain and the advice all take part. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MediaControllerTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final MediaClient CHAT = new MediaClient("chat", "chat/", "wely-chat-media");

    @MockitoBean
    private MediaService mediaService;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(SECRET_HEADER, "chat-test-secret")
                .build();
    }

    private static MultipartBodyBuilder form(byte[] file, String key, String profile) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        if (file != null) {
            builder.part("file", new ByteArrayResource(file) {
                @Override
                public String getFilename() {
                    return "upload.png";
                }
            }).contentType(MediaType.IMAGE_PNG);
        }
        if (key != null) {
            builder.part("key", key);
        }
        if (profile != null) {
            builder.part("profile", profile);
        }
        return builder;
    }

    private WebTestClient.ResponseSpec upload(MultipartBodyBuilder form) {
        return client().post().uri("/internal/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .exchange();
    }

    @Test
    void store_shouldStoreAsTheCallingClient_andAnswer201() {
        when(mediaService.store(eq(CHAT), eq("chat/c1/m1.jpg"), any(), eq(MediaProfile.PHOTO)))
                .thenReturn(Mono.just(new StoredMedia("chat/c1/m1.jpg", 1600, 1200, "image/jpeg")));

        upload(form(new byte[] {1, 2, 3}, "chat/c1/m1.jpg", "PHOTO"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.key").isEqualTo("chat/c1/m1.jpg")
                .jsonPath("$.width").isEqualTo(1600)
                .jsonPath("$.height").isEqualTo(1200)
                .jsonPath("$.contentType").isEqualTo("image/jpeg");
    }

    @Test
    void store_shouldAcceptTheProfileInAnyCase() {
        when(mediaService.store(eq(CHAT), anyString(), any(), eq(MediaProfile.RECEIPT)))
                .thenReturn(Mono.just(new StoredMedia("chat/r.jpg", 1, 1, "image/jpeg")));

        upload(form(new byte[] {1}, "chat/r.jpg", "receipt")).expectStatus().isCreated();
    }

    @Test
    void store_shouldRefuseAnUnknownProfile_withoutCallingTheService() {
        upload(form(new byte[] {1}, "chat/c1/m1.jpg", "BANNER"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-001");

        verify(mediaService, never()).store(any(), any(), any(), any());
    }

    @Test
    void store_shouldRefuseAFormWithoutFile() {
        upload(form(null, "chat/c1/m1.jpg", "PHOTO"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-001");
    }

    @Test
    void store_shouldRefuseAFormWithoutKey() {
        upload(form(new byte[] {1}, null, "PHOTO"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-001");
    }

    @Test
    @DisplayName("an upload above 12 MB answers 413 and never reaches the service")
    void store_shouldRefuseAnUploadAboveTheLimit() {
        upload(form(new byte[MediaController.MAX_UPLOAD_BYTES + 1], "chat/c1/m1.jpg", "PHOTO"))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-002");

        verify(mediaService, never()).store(any(), any(), any(), any());
    }

    @Test
    void store_shouldAnswer403_whenTheKeyIsForbidden() {
        when(mediaService.store(eq(CHAT), eq("avatars/u1.jpg"), any(), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.MEDIA_KEY_FORBIDDEN)));

        upload(form(new byte[] {1}, "avatars/u1.jpg", "AVATAR"))
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-003");
    }

    @Test
    void store_shouldAnswer502_whenTheStorageIsDown() {
        when(mediaService.store(eq(CHAT), anyString(), any(), any()))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)));

        upload(form(new byte[] {1}, "chat/c1/m1.jpg", "PHOTO"))
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("MED-TEC-001");
    }

    @Test
    void delete_shouldAnswer204() {
        when(mediaService.delete(CHAT, "chat/c1/m1.jpg")).thenReturn(Mono.empty());

        client().delete().uri(uri -> uri.path("/internal/media").queryParam("key", "chat/c1/m1.jpg").build())
                .exchange()
                .expectStatus().isNoContent();

        verify(mediaService).delete(CHAT, "chat/c1/m1.jpg");
    }

    @Test
    void delete_shouldAnswer403_whenTheKeyIsForbidden() {
        when(mediaService.delete(CHAT, "events/e1.jpg"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.MEDIA_KEY_FORBIDDEN)));

        client().delete().uri(uri -> uri.path("/internal/media").queryParam("key", "events/e1.jpg").build())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-003");
    }

    @Test
    void delete_shouldAnswer400_whenTheKeyIsMissing() {
        client().delete().uri("/internal/media")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MED-REQ-000");
    }

    private WebTestClient.ResponseSpec purge(String prefix) {
        return client().delete().uri(uri -> uri.path("/internal/media/prefix").queryParam("prefix", prefix).build())
                .exchange();
    }

    @Test
    @DisplayName("a prefix purge runs as the calling client and answers 204 with no body")
    void deletePrefix_shouldAnswer204() {
        when(mediaService.deletePrefix(CHAT, "chat/c1/")).thenReturn(Mono.just(3L));

        purge("chat/c1/").expectStatus().isNoContent().expectBody().isEmpty();

        verify(mediaService).deletePrefix(CHAT, "chat/c1/");
    }

    @Test
    void deletePrefix_shouldAnswer204_whenNothingMatched() {
        when(mediaService.deletePrefix(CHAT, "chat/c1/")).thenReturn(Mono.just(0L));

        purge("chat/c1/").expectStatus().isNoContent();
    }

    @Test
    void deletePrefix_shouldAnswer403_whenThePrefixIsForbidden() {
        when(mediaService.deletePrefix(CHAT, "avatars/u1/"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.MEDIA_KEY_FORBIDDEN)));

        purge("avatars/u1/")
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("MED-BUS-003");
    }

    @Test
    void deletePrefix_shouldAnswer400_whenThePrefixIsMissing() {
        client().delete().uri("/internal/media/prefix")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MED-REQ-000");

        verify(mediaService, never()).deletePrefix(any(), any());
    }

    @Test
    void deletePrefix_shouldAnswer502_whenTheStorageIsDown() {
        when(mediaService.deletePrefix(CHAT, "chat/c1/"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE)));

        purge("chat/c1/")
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("MED-TEC-001");
    }

    @Test
    void urls_shouldAnswerTheSignedUrlsByKey() {
        when(mediaService.urls(CHAT, List.of("chat/a.jpg"), Duration.ofSeconds(600)))
                .thenReturn(Mono.just(Map.of("chat/a.jpg", "https://media/chat/a.jpg?sig")));

        client().post().uri("/internal/media/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keys\":[\"chat/a.jpg\"],\"ttlSeconds\":600}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls['chat/a.jpg']").isEqualTo("https://media/chat/a.jpg?sig");
    }

    @Test
    void urls_shouldLeaveTheTtlToTheService_whenAbsent() {
        when(mediaService.urls(eq(CHAT), eq(List.of("chat/a.jpg")), isNull())).thenReturn(Mono.just(Map.of()));

        client().post().uri("/internal/media/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keys\":[\"chat/a.jpg\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.urls").isEmpty();
    }

    @Test
    void urls_shouldRefuseABodyWithoutKeys() {
        client().post().uri("/internal/media/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ttlSeconds\":600}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MED-VAL-001")
                .jsonPath("$.errors.keys").exists();
    }

    @Test
    void urls_shouldRefuseMoreThan200Keys() {
        String keys = String.join(",", IntStream.rangeClosed(0, 200).mapToObj(i -> "\"chat/" + i + ".jpg\"").toList());

        client().post().uri("/internal/media/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"keys\":[" + keys + "]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MED-VAL-001");

        verify(mediaService, never()).urls(any(), any(), any());
    }
}
