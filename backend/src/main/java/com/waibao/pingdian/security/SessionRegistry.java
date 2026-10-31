package com.waibao.pingdian.security;

import com.waibao.pingdian.common.redis.RedisSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 单设备会话的 Redis 镜像：{@code session:user:{userId}} 存最新的 jti，TTL 与用户 token 一致。
 *
 * 数据库 session_jti 仍保留作为审计/兼容；Redis 不可用或 key 缺失时由过滤器回退数据库，
 * 避免 Redis 重启或旧数据导致全部用户掉线。
 */
@Service
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private static final String KEY_PREFIX = "session:user:";

    private final RedisSupport redis;

    public SessionRegistry(RedisSupport redis) {
        this.redis = redis;
    }

    public String key(UUID userId) {
        return redis.key(KEY_PREFIX + userId);
    }

    /**
     * 登录成功时写入最新 jti；Redis 故障只降级为“仅数据库会话”，不阻断登录。
     */
    public void bind(UUID userId, String jti, Duration ttl) {
        try {
            redis.put(key(userId), jti, ttl);
        } catch (Exception ex) {
            log.warn("写入 Redis 会话失败 userId={} type={}", userId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 退出登录时清除；Redis 故障不阻断退出。
     */
    public void clear(UUID userId) {
        try {
            redis.delete(key(userId));
        } catch (Exception ex) {
            log.warn("清除 Redis 会话失败 userId={} type={}", userId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 读取当前 jti；key 缺失或 Redis 故障都返回空，由调用方回退数据库 session_jti。
     */
    public Optional<String> currentJti(UUID userId) {
        try {
            return redis.find(key(userId));
        } catch (Exception ex) {
            log.warn("读取 Redis 会话失败 userId={} type={}", userId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
