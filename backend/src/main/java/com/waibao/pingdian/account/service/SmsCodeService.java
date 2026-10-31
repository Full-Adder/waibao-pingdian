package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.web.dto.SmsSendResult;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.redis.RedisSupport;
import com.waibao.pingdian.config.AuthProperties;
import com.waibao.pingdian.provider.SmsProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 短信验证码：5 分钟有效、一次性、同手机号 60 秒重发限频。
 *
 * 验证码与限频状态存放在 Redis（key 前缀由 app.redis.key-prefix 统一配置），
 * 多实例共享，重启不丢失：
 * - {@code sms:code:{phone}}     验证码，TTL 5 分钟，校验时 getAndDelete 保证一次性；
 * - {@code sms:cooldown:{phone}} SET NX EX 60，第二次发送返回 429 与剩余秒数。
 */
@Service
public class SmsCodeService {

    private static final String CODE_KEY = "sms:code:";
    private static final String COOLDOWN_KEY = "sms:cooldown:";

    private final SmsProvider smsProvider;
    private final AuthProperties authProperties;
    private final RedisSupport redis;

    public SmsCodeService(SmsProvider smsProvider, AuthProperties authProperties, RedisSupport redis) {
        this.smsProvider = smsProvider;
        this.authProperties = authProperties;
        this.redis = redis;
    }

    public SmsSendResult send(String phone, String scene) {
        int resendSeconds = authProperties.getCode().getResendSeconds();
        String cooldownKey = redis.key(COOLDOWN_KEY + phone);
        if (!redis.putIfAbsent(cooldownKey, "1", Duration.ofSeconds(resendSeconds))) {
            long remaining = Math.max(1, redis.remainingSeconds(cooldownKey));
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "请求过于频繁，请在 " + remaining + " 秒后重试");
        }
        String code;
        try {
            code = smsProvider.send(phone, scene == null ? "LOGIN" : scene, Map.of());
        } catch (RuntimeException ex) {
            // 供应商发送失败时释放限频，允许用户立即重试（与发送成功才计入 60 秒的行为一致）。
            releaseCooldown(cooldownKey);
            throw ex;
        }
        int ttlSeconds = authProperties.getCode().getTtlMinutes() * 60;
        redis.put(redis.key(CODE_KEY + phone), code, Duration.ofSeconds(ttlSeconds));
        return new SmsSendResult(resendSeconds, ttlSeconds);
    }

    private void releaseCooldown(String cooldownKey) {
        try {
            redis.delete(cooldownKey);
        } catch (Exception ignored) {
            // 清理失败时保留 60 秒冷却，避免在 Redis 异常状态下放开限频。
        }
    }

    /**
     * 校验通过后立即作废（一次性）。
     */
    public void verify(String phone, String code) {
        String stored = redis.getAndDelete(redis.key(CODE_KEY + phone));
        if (stored == null || !stored.equals(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码错误或已过期");
        }
    }

    /**
     * 仅用于测试重置当前 key 前缀下的验证码与限频状态。
     */
    public void clearAll() {
        redis.deleteByPattern(CODE_KEY + "*");
        redis.deleteByPattern(COOLDOWN_KEY + "*");
    }
}
