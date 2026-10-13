package com.calendar.media.domain.models;

/** A picture once stored: its key and what it became after normalization. */
public record StoredMedia(String key, int width, int height, String contentType) {
}
