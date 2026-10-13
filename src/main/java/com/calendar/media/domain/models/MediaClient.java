package com.calendar.media.domain.models;

import java.util.regex.Pattern;

/**
 * A business service allowed to call this one, confined to its own bucket and key prefix.
 *
 * @param name      the caller, as configured (users, chat, events)
 * @param keyPrefix every key it touches must start with this, e.g. {@code avatars/}
 * @param bucket    the bucket its pictures live in
 */
public record MediaClient(String name, String keyPrefix, String bucket) {

    static final int MAX_KEY_LENGTH = 512;
    private static final Pattern KEY_CHARACTERS = Pattern.compile("[A-Za-z0-9/._-]+");

    /** True when the key is well formed and inside this client's prefix. */
    public boolean owns(String key) {
        return key != null
                && key.length() <= MAX_KEY_LENGTH
                && key.length() > keyPrefix.length()
                && key.startsWith(keyPrefix)
                && !key.startsWith("/")
                && !key.contains("..")
                && KEY_CHARACTERS.matcher(key).matches();
    }
}
