package com.waibao.pingdian.common;

/**
 * 统一响应体：{code, message, data, traceId}
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, TraceId.current());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), message, data, TraceId.current());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null, TraceId.current());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, TraceId.current());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.getCode(), message, data, TraceId.current());
    }
}
