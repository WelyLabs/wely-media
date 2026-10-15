package com.calendar.media.domain.models;

import java.util.regex.Pattern;

/** A business service allowed to call this one, confined to its own bucket and key prefix. */
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

    /** True when the prefix names a folder strictly inside this client's prefix, such as {@code avatars/<id>/}. */
    public boolean ownsPrefix(String prefix) {
        return prefix != null
                && prefix.length() <= MAX_KEY_LENGTH
                && prefix.startsWith(keyPrefix)
                && prefix.endsWith("/")
                && prefix.length() > keyPrefix.length() + 1
                && !prefix.substring(keyPrefix.length()).startsWith("/")
                && !prefix.startsWith("/")
                && !prefix.contains("//")
                && !prefix.contains("..")
                && KEY_CHARACTERS.matcher(prefix).matches();
    }
}
