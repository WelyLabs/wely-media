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
}
