package com.calendar.media.exception;

/**
 * A dependency this service needs is unavailable.
 *
 * <p>Distinct from {@link BusinessException} because the two call for different responses:
 * a business failure means the caller should change the request, a technical one means the
 * caller should retry and someone should look at an incident.
 */
public class TechnicalException extends RuntimeException {

    private final TechnicalErrorCode errorCode;

    public TechnicalException(TechnicalErrorCode errorCode) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
    }

    /** Keeps the original failure, so the log shows what actually broke. */
    public TechnicalException(TechnicalErrorCode errorCode, Throwable cause) {
        super(errorCode.getDetail(), cause);
        this.errorCode = errorCode;
    }

    public TechnicalErrorCode getErrorCode() {
        return errorCode;
    }
}
