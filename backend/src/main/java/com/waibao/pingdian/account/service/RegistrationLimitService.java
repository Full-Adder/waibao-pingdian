package com.waibao.pingdian.account.service;

import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.redis.RedisSupport;
import com.waibao.pingdian.config.AuthProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 同一 IP 24 小时内注册上限（仅统计新账号创建，登录不计）。
 *
 * 计数存放在 Redis：{@code reg:ip:{ip}:{yyyyMMdd}}（业务时区 Asia/Shanghai 日切），
 * INCR 并在首次创建时设置 24h 过期；多实例共享，重启不清零。
 */
@Service
public class RegistrationLimitService {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String KEY_PREFIX = "reg:ip:";

    private final AuthProperties authProperties;
    private final RedisSupport redis;

    public RegistrationLimitService(AuthProperties authProperties, RedisSupport redis) {
        this.authProperties = authProperties;
        this.redis = redis;
    }

    public void assertCanRegister(String ip) {
        long registered = currentCount(ip);
        if (registered >= authProperties.getRegister().getIpDailyLimit()) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "同一 IP 24 小时内最多注册 " + authProperties.getRegister().getIpDailyLimit()
                            + " 个账号，请稍后再试");
        }
    }

    /**
     * 记录一次成功的新账号创建，返回该 IP 当日窗口内的累计注册数。
     */
    public int recordRegistration(String ip) {
        long value = redis.incrementWithTtl(key(ip), WINDOW);
        return (int) value;
    }

    private long currentCount(String ip) {
        return redis.find(key(ip))
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException ex) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private String key(String ip) {
        return redis.key(KEY_PREFIX + ip + ":" + LocalDate.now(BUSINESS_ZONE).format(DAY_FORMAT));
    }

    /**
     * 仅用于测试重置当前 key 前缀下的 IP 注册计数。
     */
    public void clearAll() {
        redis.deleteByPattern(KEY_PREFIX + "*");
    }
}
