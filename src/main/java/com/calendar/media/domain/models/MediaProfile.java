package com.calendar.media.domain.models;

/** How a picture is reshaped before it is stored; the caller picks the profile per upload. */
public enum MediaProfile {

    /** Center-cropped square, 512 px a side. */
    AVATAR,

    /** Longest side at most 1600 px, never upscaled. */
    PHOTO,

    /** Width at most 1600 px, ratio kept, never upscaled. */
    COVER,

    /** Longest side at most 2000 px, never upscaled: a receipt must stay legible. */
    RECEIPT
}
