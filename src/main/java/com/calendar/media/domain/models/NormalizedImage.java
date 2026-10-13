package com.calendar.media.domain.models;

/** The re-encoded picture, ready to store. */
public record NormalizedImage(byte[] content, String contentType, int width, int height) {
}
