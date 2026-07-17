package com.dart.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String suggestion;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.suggestion = null;
    }

    public BusinessException(ErrorCode errorCode, String suggestion) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.suggestion = suggestion;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getSuggestion() {
        return suggestion;
    }
}
