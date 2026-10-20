package com.calendar.media.exception;

/** A refusal with a stable code; carries no framework type, so the domain can raise it. */
public class BusinessException extends RuntimeException {

    private final BusinessErrorCode errorCode;

    public BusinessException(BusinessErrorCode errorCode) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
