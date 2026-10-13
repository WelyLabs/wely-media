package com.calendar.media.exception;

/** A refusal with a stable code; carries no framework type, so the domain can raise it. */
public class MediaException extends RuntimeException {

    private final MediaErrorCode errorCode;

    public MediaException(MediaErrorCode errorCode) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
    }

    public MediaErrorCode getErrorCode() {
        return errorCode;
    }
}
