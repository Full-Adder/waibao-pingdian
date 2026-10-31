package com.waibao.pingdian.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码：code 为对外业务码，httpStatus 为 HTTP 状态码。
 */
public enum ErrorCode {

    SUCCESS(0, "OK", HttpStatus.OK),
    BAD_REQUEST(400, "请求参数错误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(401, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "无权限执行该操作", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(409, "资源冲突", HttpStatus.CONFLICT),
    TOO_MANY_REQUESTS(429, "操作过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(500, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(503, "服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
