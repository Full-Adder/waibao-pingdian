package com.waibao.pingdian.common.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 Redis 的分布式锁：SET NX PX 抢占，Lua 脚本仅释放自己持有的 token。
 * 定时任务入口用它防止多实例重复执行；后续切换 XXL-Job 时保留。
 */
@Service
public class RedisLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final String LOCK_PREFIX = "lock:";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate template;
    private final RedisSupport redis;

    public RedisLockService(StringRedisTemplate template, RedisSupport redis) {
        this.template = template;
        this.redis = redis;
    }

    /**
     * 抢占锁；成功返回持有 token，失败返回空（表示已被其他实例持有）。
     */
    public Optional<String> tryLock(String name, Duration ttl) {
        String token = UUID.randomUUID().toString();
        boolean acquired = redis.putIfAbsent(redis.key(LOCK_PREFIX + name), token, ttl);
        return acquired ? Optional.of(token) : Optional.empty();
    }

    /**
     * 仅当锁内 token 与传入 token 一致时才删除，避免误删他实例的锁。
     */
    public boolean unlock(String name, String token) {
        if (token == null) {
            return false;
        }
        try {
            Long result = template.execute(UNLOCK_SCRIPT, List.of(redis.key(LOCK_PREFIX + name)), token);
            return result != null && result > 0;
        } catch (Exception ex) {
            log.warn("释放 Redis 锁失败 name={} type={}", name, ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 拿到锁则执行并释放，拿不到锁直接返回 skipped（不阻塞、不执行）。
     */
    public <T> LockResult<T> runWithLock(String name, Duration ttl, Supplier<T> action) {
        Optional<String> token = tryLock(name, ttl);
        if (token.isEmpty()) {
            log.info("Redis 锁已被占用，跳过执行 name={}", name);
            return LockResult.skipped();
        }
        try {
            return LockResult.executed(action.get());
        } finally {
            unlock(name, token.get());
        }
    }

    /**
     * 每日任务锁名：任务名 + 业务日期。
     */
    public static String dailyKey(String jobName) {
        return jobName + ":" + LocalDate.now(BUSINESS_ZONE).format(DAY_FORMAT);
    }

    /**
     * 高频任务锁名：任务名 + 5 分钟时间片，避免同一时间片被多实例重复执行。
     */
    public static String fiveMinuteKey(String jobName) {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime slot = now.withMinute((now.getMinute() / 5) * 5).withSecond(0).withNano(0);
        return jobName + ":" + slot.format(MINUTE_FORMAT);
    }

    public record LockResult<T>(boolean acquired, T value) {
        public static <T> LockResult<T> skipped() {
            return new LockResult<>(false, null);
        }

        public static <T> LockResult<T> executed(T value) {
            return new LockResult<>(true, value);
        }
    }
}
