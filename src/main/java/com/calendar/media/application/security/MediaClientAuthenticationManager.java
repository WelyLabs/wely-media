package com.calendar.media.application.security;

import com.calendar.media.domain.models.MediaClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the presented secret to the {@link MediaClient} it belongs to.
 *
 * <p>Every client is compared, in constant time, on SHA-256 digests so the secrets' lengths do
 * not leak either. A client with a blank secret is left out: an empty header never matches.
 */
public class MediaClientAuthenticationManager implements ReactiveAuthenticationManager {

    private final List<Registered> clients;

    public MediaClientAuthenticationManager(List<RegisteredClient> configured) {
        this.clients = configured.stream()
                .filter(client -> client.secret() != null && !client.secret().isBlank())
                .map(client -> new Registered(client.client(), digest(client.secret())))
                .toList();
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        return Mono.fromSupplier(() -> resolve(String.valueOf(authentication.getCredentials())))
                .flatMap(client -> client
                        .map(found -> Mono.<Authentication>just(UsernamePasswordAuthenticationToken.authenticated(found, null, List.of())))
                        .orElseGet(() -> Mono.error(new BadCredentialsException("Unknown internal secret"))));
    }

    /** No early exit: the time taken does not tell which client, if any, matched. */
    public Optional<MediaClient> resolve(String secret) {
        byte[] presented = digest(secret);
        MediaClient match = null;
        for (Registered client : clients) {
            if (MessageDigest.isEqual(presented, client.digest())) {
                match = client.client();
            }
        }
        return Optional.ofNullable(match);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e);
        }
    }

    /** A client as configured, with the secret it must present. */
    public record RegisteredClient(MediaClient client, String secret) {
    }

    private record Registered(MediaClient client, byte[] digest) {
    }
}
