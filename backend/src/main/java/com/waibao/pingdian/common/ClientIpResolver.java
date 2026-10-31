package com.waibao.pingdian.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP：优先取反向代理写入的 X-Forwarded-For 首个地址，否则回退远端地址。
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
