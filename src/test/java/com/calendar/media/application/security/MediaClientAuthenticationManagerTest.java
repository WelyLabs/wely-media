package com.calendar.media.application.security;

import com.calendar.media.application.security.MediaClientAuthenticationManager.RegisteredClient;
import com.calendar.media.domain.models.MediaClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaClientAuthenticationManagerTest {

    private static final MediaClient USERS = new MediaClient("users", "avatars/", "wely-avatars");
    private static final MediaClient CHAT = new MediaClient("chat", "chat/", "wely-chat-media");
    private static final MediaClient EVENTS = new MediaClient("events", "events/", "wely-event-media");

    private final MediaClientAuthenticationManager manager = new MediaClientAuthenticationManager(List.of(
            new RegisteredClient(USERS, "users-secret"),
            new RegisteredClient(CHAT, "chat-secret"),
            new RegisteredClient(EVENTS, "")));

    @Test
    void authenticate_shouldMakeTheMatchingClientThePrincipal() {
        StepVerifier.create(manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(null, "chat-secret")))
                .assertNext(authentication -> {
                    assertThat(authentication.isAuthenticated()).isTrue();
                    assertThat(authentication.getPrincipal()).isEqualTo(CHAT);
                    assertThat(authentication.getCredentials()).isNull();
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void authenticate_shouldRefuseAnUnknownSecret() {
        StepVerifier.create(manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(null, "guess")))
                .expectError(BadCredentialsException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void resolve_shouldFindEachClientByItsOwnSecret() {
        assertThat(manager.resolve("users-secret")).contains(USERS);
        assertThat(manager.resolve("chat-secret")).contains(CHAT);
        assertThat(manager.resolve("chat-secret ")).isEmpty();
    }

    @Test
    void resolve_shouldNeverMatchAClientWithABlankSecret() {
        assertThat(manager.resolve("")).isEmpty();
        assertThat(manager.resolve(" ")).isEmpty();
    }

    @Test
    void constructor_shouldSkipAClientWithoutSecret() {
        MediaClientAuthenticationManager none = new MediaClientAuthenticationManager(List.of(new RegisteredClient(USERS, null)));

        assertThat(none.resolve("null")).isEmpty();
    }
}
