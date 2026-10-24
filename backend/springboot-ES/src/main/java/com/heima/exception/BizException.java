package com.heima.exception;

import com.heima.common.ErrorCode;

public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
