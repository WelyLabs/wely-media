package com.calendar.media.exception;

import org.springframework.http.HttpStatus;

/**
 * Failures of a dependency rather than of the request.
 *
 * <p>Each names which dependency broke, so an incident can be routed without reading the
 * logs. All of them answer 502 rather than 500: the request was valid and this service is
 * running — something it depends on is not, and 502 says exactly that.
 *
 * <p>Accessors are written out because this service does not use Lombok.
 */
public enum TechnicalErrorCode {

    STORAGE_UNAVAILABLE(
            "MED-TEC-001",
            "Storage unavailable",
            "The object storage could not be reached; try again shortly.",
            HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;

    TechnicalErrorCode(String code, String title, String detail, HttpStatus httpStatus) {
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.httpStatus = httpStatus;
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

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
