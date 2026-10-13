package com.calendar.media.application.dtos;

/** What a caller keeps after an upload: the key, never a URL. */
public record StoredMediaResponse(String key, int width, int height, String contentType) {
}
