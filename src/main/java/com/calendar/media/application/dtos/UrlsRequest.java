package com.calendar.media.application.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Keys to sign, and how long the URLs should live (clamped to 1 min..12 h, 1 h when absent). */
public record UrlsRequest(
        @NotNull @Size(max = 200) List<@NotBlank String> keys,
        Long ttlSeconds) {
}
