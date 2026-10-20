package com.calendar.media.infrastructure.storage.adapters;

import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.BusinessException;
import com.calendar.media.exception.TechnicalErrorCode;
import com.calendar.media.exception.TechnicalException;
import com.calendar.media.infrastructure.storage.signing.GarageRequestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requests go to a stub exchange: what is checked is what the adapter sends, not Garage. */
class GarageObjectStorageAdapterTest {

    private static final int PAGE_SIZE = 200;

    private final List<ClientRequest> sent = new ArrayList<>();
    /** Answered first, one per request, before falling back to {@link #answer}. */
    private final Deque<Mono<ClientResponse>> pages = new ArrayDeque<>();
    private Mono<ClientResponse> answer;
    private GarageObjectStorageAdapter storage;

    @BeforeEach
    void setUp() {
        answer = Mono.just(ClientResponse.create(HttpStatus.OK).build());
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    sent.add(request);
                    return pages.isEmpty() ? answer : pages.removeFirst();
                })
                .build();
        GarageRequestSigner signer = new GarageRequestSigner("GK000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000000", "garage", Clock.systemUTC());
        storage = new GarageObjectStorageAdapter(client, signer, URI.create("http://dev-wely-garage-service:3900"),
                URI.create("https://dev-media.welylabs.app"), PAGE_SIZE);
    }

    private static Mono<ClientResponse> listing(String nextToken, String... keys) {
        StringBuilder contents = new StringBuilder();
        for (String key : keys) {
            contents.append("<Contents><Key>").append(key).append("</Key><Size>3</Size></Contents>");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<IsTruncated>" + (nextToken != null) + "</IsTruncated>"
                + (nextToken == null ? "" : "<NextContinuationToken>" + nextToken + "</NextContinuationToken>")
                + contents + "</ListBucketResult>";
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .body(xml)
                .build());
    }

    private static boolean unavailable(Throwable error) {
        return error instanceof TechnicalException technical
                && technical.getErrorCode() == TechnicalErrorCode.STORAGE_UNAVAILABLE;
    }

    @Test
    @DisplayName("a signed URL names the public host, the bucket path and its expiry, and asks for a private cache")
    void presignGet_shouldTargetThePublicHostWithAnExpiry() {
        URI url = URI.create(storage.presignGet("wely-event-media", "events/e1/cover.jpg", Duration.ofMinutes(10)));

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("dev-media.welylabs.app");
        assertThat(url.getPath()).isEqualTo("/wely-event-media/events/e1/cover.jpg");
        assertThat(url.getQuery())
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-Signature=")
                .contains("response-cache-control=private, max-age=3600");
    }

    @Test
    void put_shouldWriteTheObjectInternally_withItsTypeAndAPrivateCacheControl() {
        StepVerifier.create(storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg")).expectComplete().verify(Duration.ofSeconds(10));

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.PUT);
        assertThat(request.url().getAuthority()).isEqualTo("dev-wely-garage-service:3900");
        assertThat(request.url().getPath()).isEqualTo("/wely-chat-media/chat/c1/m1.jpg");
        assertThat(request.url().getQuery()).contains("X-Amz-Expires=60").contains("X-Amz-Signature=");
        assertThat(request.headers().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("image/jpeg");
        assertThat(request.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo(GarageObjectStorageAdapter.CACHE_CONTROL);
    }

    @Test
    void put_shouldSignOnlyWhenSubscribed() {
        // Signed lazily, so a retry or a late subscription never sends an already-ageing URL.
        storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg");

        assertThat(sent).isEmpty();
    }

    @Test
    void put_shouldFail_whenGarageRefuses() {
        answer = Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());

        StepVerifier.create(storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void put_shouldFail_whenGarageIsUnreachable() {
        answer = Mono.error(new IOException("Connection refused"));

        StepVerifier.create(storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the original Garage failure is kept as the cause, so the ERROR log shows it")
    void put_shouldKeepTheOriginalFailureAsCause_whenGarageIsUnreachable() {
        IOException refused = new IOException("Connection refused");
        answer = Mono.error(refused);

        StepVerifier.create(storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                .expectErrorSatisfies(error -> {
                    assertThat(unavailable(error)).isTrue();
                    assertThat(error.getCause()).isSameAs(refused);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void put_shouldLetABusinessExceptionThroughUnchanged() {
        answer = Mono.error(new BusinessException(BusinessErrorCode.MEDIA_INVALID));

        StepVerifier.create(storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                .expectErrorMatches(error -> error instanceof BusinessException business
                        && business.getErrorCode() == BusinessErrorCode.MEDIA_INVALID)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void put_shouldLetATechnicalExceptionThroughUnchanged() {
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE);
        answer = Mono.error(failure);

        StepVerifier.create(storage.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                .expectErrorMatches(error -> error == failure)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void delete_shouldRemoveTheObjectInternally() {
        answer = Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());

        StepVerifier.create(storage.delete("wely-avatars", "avatars/u1/a.jpg")).expectComplete().verify(Duration.ofSeconds(10));

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
        assertThat(request.url().getAuthority()).isEqualTo("dev-wely-garage-service:3900");
        assertThat(request.url().getPath()).isEqualTo("/wely-avatars/avatars/u1/a.jpg");
    }

    @Test
    void delete_shouldFail_whenGarageIsDown() {
        answer = Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        StepVerifier.create(storage.delete("wely-avatars", "avatars/u1/a.jpg"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a listing is a signed ListObjectsV2 GET on the bucket, at the internal host, one page at a time")
    void list_shouldSignAListObjectsV2RequestToTheInternalHost() {
        pages.add(listing(null, "avatars/u1/a.jpg"));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/")).expectNext("avatars/u1/a.jpg").expectComplete().verify(Duration.ofSeconds(10));

        ClientRequest request = sent.getFirst();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.url().getAuthority()).isEqualTo("dev-wely-garage-service:3900");
        assertThat(request.url().getPath()).isEqualTo("/wely-avatars");
        assertThat(request.url().getQuery())
                .contains("list-type=2")
                .contains("prefix=avatars/u1/")
                .contains("max-keys=" + PAGE_SIZE)
                .contains("X-Amz-Expires=60")
                .contains("X-Amz-Signature=")
                .doesNotContain("continuation-token");
    }

    @Test
    @DisplayName("a truncated listing is followed with its continuation token until the last page")
    void list_shouldFollowTheContinuationToken() {
        pages.add(listing("t1", "avatars/u1/a.jpg", "avatars/u1/b.jpg"));
        pages.add(listing("t2", "avatars/u1/c.jpg"));
        pages.add(listing(null, "avatars/u1/d.jpg"));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectNext("avatars/u1/a.jpg", "avatars/u1/b.jpg", "avatars/u1/c.jpg", "avatars/u1/d.jpg")
                .expectComplete().verify(Duration.ofSeconds(10));

        assertThat(sent).hasSize(3);
        assertThat(sent.get(1).url().getQuery()).contains("continuation-token=t1").contains("prefix=avatars/u1/");
        assertThat(sent.get(2).url().getQuery()).contains("continuation-token=t2");
    }

    @Test
    @DisplayName("nothing under the prefix is an empty listing, not an error")
    void list_shouldBeEmpty_whenNothingMatches() {
        pages.add(listing(null));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/")).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("a token handed back unchanged fails the listing instead of reporting it complete")
    void list_shouldFail_whenTheTokenRepeats() {
        pages.add(listing("t1", "avatars/u1/a.jpg"));
        pages.add(listing("t1", "avatars/u1/b.jpg"));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectNext("avatars/u1/a.jpg")
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));

        assertThat(sent).hasSize(2);
    }

    @Test
    void list_shouldSignOnlyWhenSubscribed() {
        storage.list("wely-avatars", "avatars/u1/");

        assertThat(sent).isEmpty();
    }

    @Test
    void list_shouldFail_whenGarageRefuses() {
        answer = Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void list_shouldFail_whenGarageIsUnreachable() {
        answer = Mono.error(new IOException("Connection refused"));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an answer that is not a listing, or no answer at all, fails rather than reading as empty")
    void list_shouldFail_whenTheAnswerIsNotAListing() {
        pages.add(Mono.just(ClientResponse.create(HttpStatus.OK).body("<html><body>Bad gateway</body></html>").build()));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a truncated page without a token fails the listing instead of ending it early")
    void list_shouldFail_whenATruncatedPageHasNoToken() {
        pages.add(Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .body("<ListBucketResult><IsTruncated>true</IsTruncated>"
                        + "<Contents><Key>avatars/u1/a.jpg</Key></Contents></ListBucketResult>")
                .build()));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a failure on a later page fails the listing")
    void list_shouldFail_whenALaterPageFails() {
        pages.add(listing("t1", "avatars/u1/a.jpg"));
        pages.add(Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        StepVerifier.create(storage.list("wely-avatars", "avatars/u1/"))
                .expectNext("avatars/u1/a.jpg")
                .expectErrorMatches(GarageObjectStorageAdapterTest::unavailable)
                .verify(Duration.ofSeconds(10));
    }

    // --- Secrets in the logged failure ------------------------------------------------------------
    // The handler logs the whole cause chain at ERROR. These run the real HTTP client, so the chain is
    // exactly what production would log, and guard that neither key nor signature ever reaches it:
    // WebClient formats the request URI without its query, where the presigned credentials live.

    private static final String ACCESS_KEY = "GKs3cr3tACCESSKEY0000000000";
    private static final String SECRET_KEY = "s3cr3tSECRETKEY";

    private static GarageObjectStorageAdapter realClientTowards(URI endpoint) {
        GarageRequestSigner signer = new GarageRequestSigner(ACCESS_KEY, SECRET_KEY, "garage", Clock.systemUTC());
        return new GarageObjectStorageAdapter(WebClient.builder().build(), signer, endpoint, endpoint, PAGE_SIZE);
    }

    private static String wholeChain(Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

    private static void assertNoCredentials(Throwable error) {
        assertThat(unavailable(error)).isTrue();
        assertThat(wholeChain(error))
                .contains("/wely-chat-media/chat/c1/m1.jpg")
                .doesNotContain(SECRET_KEY, ACCESS_KEY, "X-Amz-Signature", "X-Amz-Credential");
    }

    @Test
    @DisplayName("an unreachable Garage leaves no key or signature in the logged cause chain")
    void put_shouldKeepCredentialsOutOfTheFailure_whenGarageIsUnreachable() {
        // Port 1 on loopback: refused at once, no timeout to wait for.
        GarageObjectStorageAdapter unreachable = realClientTowards(URI.create("http://127.0.0.1:1"));

        StepVerifier.create(unreachable.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                .expectErrorSatisfies(GarageObjectStorageAdapterTest::assertNoCredentials)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an error answer from Garage leaves no key or signature in the logged cause chain")
    void put_shouldKeepCredentialsOutOfTheFailure_whenGarageAnswersAnError() {
        DisposableServer garage = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.status(403).sendString(Mono.just("<Error>AccessDenied</Error>")))
                .bindNow();
        try {
            GarageObjectStorageAdapter refusing = realClientTowards(URI.create("http://127.0.0.1:" + garage.port()));

            StepVerifier.create(refusing.put("wely-chat-media", "chat/c1/m1.jpg", new byte[] {1}, "image/jpeg"))
                    .expectErrorSatisfies(GarageObjectStorageAdapterTest::assertNoCredentials)
                    .verify(Duration.ofSeconds(10));
        } finally {
            garage.disposeNow();
        }
    }
}
