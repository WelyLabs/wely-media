package com.calendar.media.application.dtos;

import java.util.Map;

/** Signed read URL by key. */
public record UrlsResponse(Map<String, String> urls) {
}
