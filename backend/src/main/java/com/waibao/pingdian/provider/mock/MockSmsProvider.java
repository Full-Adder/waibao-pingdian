package com.waibao.pingdian.provider.mock;

import com.waibao.pingdian.common.SensitiveDataMasker;
import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.provider.ProviderException;
import com.waibao.pingdian.provider.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 短信：固定验证码（默认 123456），同时写入服务端日志。
 * 测试可用 providers.mock.sms.fail-first-attempts 对时限类通知注入失败，不影响登录验证码。
 */
public class MockSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(MockSmsProvider.class);

    private static final Set<String> NOTIFICATION_TEMPLATES = Set.of(
            "DISPUTE_STARTED", "EVIDENCE_REMINDER", "RULING_RESULT",
            "VERIFICATION_REJECTED", "APPEAL_RESULT");

    private final ProvidersProperties properties;
    private final AtomicInteger notificationAttempts = new AtomicInteger();
    private final Map<String, AtomicInteger> templateCounts = new ConcurrentHashMap<>();

    public MockSmsProvider(ProvidersProperties properties) {
        this.properties = properties;
    }

    @Override
    public String send(String phone, String template, Map<String, String> params) {
        int attempt = templateCounts.computeIfAbsent(template, key -> new AtomicInteger())
                .incrementAndGet();
        if (NOTIFICATION_TEMPLATES.contains(template)) {
            int failFirst = properties.getMock().getSms().getFailFirstAttempts();
            if (failFirst > 0 && notificationAttempts.incrementAndGet() <= failFirst) {
                throw new ProviderException("mock sms injected failure #" + attempt);
            }
        }
        String code = properties.getMock().getSms().getFixedCode();
        log.info("[mock-sms] phone={} template={} params={} code={}",
                SensitiveDataMasker.maskPhone(phone), template, params, code);
        return code;
    }

    /**
     * 测试辅助：重置失败注入计数（不清除按模板统计的调用次数）。
     */
    public void resetNotificationFailureCounter() {
        notificationAttempts.set(0);
    }

    public int callCount(String template) {
        AtomicInteger count = templateCounts.get(template);
        return count == null ? 0 : count.get();
    }
}
