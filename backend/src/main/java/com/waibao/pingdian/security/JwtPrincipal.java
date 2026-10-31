package com.waibao.pingdian.security;

/**
 * JWT 解析后的身份信息。
 */
public record JwtPrincipal(String subjectId, String role, String audience, String jti) {
}
