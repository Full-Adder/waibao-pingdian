package com.waibao.pingdian.common;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 可选的结构化错误负载（字段级错误、冲突原 reviewId、剩余天数等）。
     */
    public Object getDetails() {
        return details;
    }
}
