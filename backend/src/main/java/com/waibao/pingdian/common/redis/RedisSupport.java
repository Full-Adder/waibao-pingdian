package com.waibao.pingdian.common.redis;

import com.waibao.pingdian.config.AppRedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 轻量封装：统一 key 前缀、只存字符串/JSON 文本（StringRedisTemplate，不使用 JDK 序列化）。
 *
 * 缓存类读取失败由调用方降级；Redis 不可用时 {@link #ping()} 返回 false，健康检查据此返回 503。
 */
@Component
public class RedisSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisSupport.class);

    private final StringRedisTemplate template;
    private final String keyPrefix;

    public RedisSupport(StringRedisTemplate template, AppRedisProperties properties) {
        this.template = template;
        this.keyPrefix = properties.getKeyPrefix() == null ? "" : properties.getKeyPrefix();
    }

    /**
     * 拼接带环境前缀的完整 key。
     */
    public String key(String suffix) {
        return keyPrefix + suffix;
    }

    public String prefix() {
        return keyPrefix;
    }

    public Optional<String> find(String key) {
        return Optional.ofNullable(template.opsForValue().get(key));
    }

    public void put(String key, String value, Duration ttl) {
        template.opsForValue().set(key, value, ttl);
    }

    public boolean putIfAbsent(String key, String value, Duration ttl) {
        Boolean result = template.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(result);
    }

    public String getAndDelete(String key) {
        return template.opsForValue().getAndDelete(key);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(template.delete(key));
    }

    /**
     * 剩余存活秒数；key 不存在返回 -2，无过期时间返回 -1。
     */
    public long remainingSeconds(String key) {
        Long ttl = template.getExpire(key, TimeUnit.SECONDS);
        return ttl == null ? -2 : ttl;
    }

    /**
     * INCR 并仅在首次创建时设置过期时间（用于 24h 窗口计数）。
     */
    public long incrementWithTtl(String key, Duration ttl) {
        Long value = template.opsForValue().increment(key);
        if (value != null && value == 1L) {
            template.expire(key, ttl);
        }
        return value == null ? 0 : value;
    }

    /**
     * 按前缀模式删除（SCAN 遍历，不使用阻塞的 KEYS）。
     */
    public int deleteByPattern(String suffixPattern) {
        String pattern = keyPrefix + suffixPattern;
        AtomicInteger deleted = new AtomicInteger();
        template.execute((RedisConnection connection) -> {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    connection.del(cursor.next());
                    deleted.incrementAndGet();
                }
            }
            return null;
        });
        return deleted.get();
    }

    /**
     * Redis PING；异常（连接失败/超时）视为不可用，仅记录异常类型，不输出连接串与密码。
     */
    public boolean ping() {
        try {
            String pong = template.execute((RedisCallback<String>) connection -> connection.ping());
            return pong != null && "PONG".equalsIgnoreCase(pong);
        } catch (Exception ex) {
            log.warn("Redis PING 失败：{}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
