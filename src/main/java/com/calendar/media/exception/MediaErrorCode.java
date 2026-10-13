package com.calendar.media.exception;

/**
 * Outcomes this service refuses on, each with a stable code.
 *
 * <p>{@code code} is what a caller branches on; the status is a plain int so the domain can raise
 * these without depending on Spring.
 */
public enum MediaErrorCode {

    MEDIA_INVALID(
            "MED-BUS-001",
            "Picture not accepted",
            "Send a JPEG, PNG, GIF or BMP picture of at most 8000 pixels per side and 40 megapixels.",
            400),

    MEDIA_TOO_LARGE(
            "MED-BUS-002",
            "Picture too large",
            "The picture must be smaller than 12 MB.",
            413),

    MEDIA_KEY_FORBIDDEN(
            "MED-BUS-003",
            "Key not allowed",
            "The key is malformed or outside the prefix granted to this caller.",
            403),

    CLIENT_UNKNOWN(
            "MED-REQ-001",
            "Unknown caller",
            "The X-Internal-Secret header is missing or matches no configured client.",
            401),

    STORAGE_UNAVAILABLE(
            "MED-TEC-001",
            "Storage unavailable",
            "The object storage could not be reached; try again shortly.",
            502);

    private final String code;
    private final String title;
    private final String detail;
    private final int status;

    MediaErrorCode(String code, String title, String detail, int status) {
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public int getStatus() {
        return status;
    }
}
