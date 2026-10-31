package com.waibao.pingdian.security;

import com.waibao.pingdian.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

/**
 * JWT 签发与解析：用户 audience=user，管理端 audience=admin。用户 token 携带 jti 用于单设备会话。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String JTI_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int JTI_LENGTH = 32;

    private final AuthProperties authProperties;
    private final SecureRandom random = new SecureRandom();
    private SecretKey key;

    public JwtService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostConstruct
    void init() {
        String secret = authProperties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("JWT_SECRET 未配置，用户/管理端 token 将无法签发与解析");
            return;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            log.warn("JWT_SECRET 长度不足 32 字节，已禁用 token 签发与解析");
            return;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public IssuedToken issueUserToken(String userId, String role, String jti) {
        return issue(userId, role, "user", jti, authProperties.getJwt().getUserTtlMinutes());
    }

    public IssuedToken issueAdminToken(String adminId, String role, String jti) {
        return issue(adminId, role, "admin", jti, authProperties.getJwt().getAdminTtlMinutes());
    }

    /**
     * 用户 token 有效期，用于 Redis 单设备会话 key 的 TTL。
     */
    public Duration userTokenTtl() {
        return Duration.ofMinutes(authProperties.getJwt().getUserTtlMinutes());
    }

    public String newSessionJti() {
        StringBuilder builder = new StringBuilder(JTI_LENGTH);
        for (int i = 0; i < JTI_LENGTH; i++) {
            builder.append(JTI_ALPHABET.charAt(random.nextInt(JTI_ALPHABET.length())));
        }
        return builder.toString();
    }

    public Optional<JwtPrincipal> parse(String token, String expectedAudience) {
        if (key == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            Claims claims = jws.getPayload();
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                return Optional.empty();
            }
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            String role = claims.get("role", String.class);
            String jti = claims.getId();
            return Optional.of(new JwtPrincipal(subject, role, expectedAudience, jti));
        } catch (Exception ex) {
            log.debug("token 解析失败：{}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private IssuedToken issue(String subject, String role, String audience, String jti, int ttlMinutes) {
        if (key == null) {
            throw new IllegalStateException("JWT_SECRET 未配置或长度不足 32 字节，无法签发 token");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        String token = Jwts.builder()
                .subject(subject)
                .audience().add(audience).and()
                .id(jti)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
