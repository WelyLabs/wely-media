package com.calendar.media.domain.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MediaClientTest {

    private final MediaClient users = new MediaClient("users", "avatars/", "wely-avatars");

    @ParameterizedTest
    @ValueSource(strings = {"avatars/u1/a.jpg", "avatars/u-1_2/v3.jpeg", "avatars/x"})
    void owns_shouldAcceptAKeyInsideThePrefix(String key) {
        assertThat(users.owns(key)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "chat/c1/m1.jpg",          // another client's prefix
            "avatars/",                // the prefix alone names no object
            "avatarsX/u1.jpg",         // looks alike, is not inside
            "/avatars/u1.jpg",         // leading slash
            "avatars/../chat/m1.jpg",  // traversal
            "avatars/u1/a b.jpg",      // space
            "avatars/u1/é.jpg",        // non-ASCII
            "avatars/u1?x=1"           // query characters
    })
    void owns_shouldRefuseAKeyOutsideThePrefixOrMalformed(String key) {
        assertThat(users.owns(key)).isFalse();
    }

    @Test
    void owns_shouldRefuseAKeyLongerThanTheLimit() {
        String atLimit = "avatars/" + "a".repeat(MediaClient.MAX_KEY_LENGTH - "avatars/".length());
        assertThat(users.owns(atLimit)).isTrue();
        assertThat(users.owns(atLimit + "a")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatars/u1/", "avatars/u1/sub/", "avatars/89a380b8-f66a-44d0-b9d9-616241080430/"})
    void ownsPrefix_shouldAcceptAPrefixInsideTheClientsPrefix(String prefix) {
        assertThat(users.ownsPrefix(prefix)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "avatars/",        // the client's whole prefix
            "avatars",         // not even the prefix
            "avatars/u1",      // no closing slash: would match avatars/u10/ too
            "avatars//",       // empty segment
            "avatars/u1//",    // empty segment
            "avatars/../",     // traversal
            "/avatars/u1/",    // leading slash
            "chat/c1/",        // another client's prefix
            "avatarsX/u1/",    // looks alike, is not inside
            "avatars/u 1/",    // space
            "avatars/u1?x=1/"  // query characters
    })
    void ownsPrefix_shouldRefuseABareMalformedOrForeignPrefix(String prefix) {
        assertThat(users.ownsPrefix(prefix)).isFalse();
    }

    @Test
    void ownsPrefix_shouldRefuseAPrefixLongerThanTheLimit() {
        String atLimit = "avatars/" + "a".repeat(MediaClient.MAX_KEY_LENGTH - "avatars/".length() - 1) + "/";
        String overLimit = "avatars/" + "a".repeat(MediaClient.MAX_KEY_LENGTH - "avatars/".length()) + "/";
        assertThat(users.ownsPrefix(atLimit)).isTrue();
        assertThat(users.ownsPrefix(overLimit)).isFalse();
    }
}
