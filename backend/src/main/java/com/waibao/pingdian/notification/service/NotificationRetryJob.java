package com.waibao.pingdian.notification.service;

import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.common.redis.RedisLockService;
import com.waibao.pingdian.notification.domain.Notification;
import com.waibao.pingdian.notification.repo.NotificationRepository;
import com.waibao.pingdian.provider.SmsProvider;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短信失败重试（XXL-Job 每 5 分钟触发）：扫描 FAILED 且 retry_count &lt; 3 的 SMS/BOTH 通知，
 * 作为 RabbitMQ 延迟重试的兜底。
 * retry_count 记录累计失败次数（首次失败为 1），达到 3 后保持 FAILED 并保留 last_error。
 * 入口使用 Redis 分布式锁（按 5 分钟时间片），防止多实例重复重试。
 */
@Service
public class NotificationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryJob.class);

    private static final int MAX_RETRIES = 3;
    private static final List<String> SMS_CHANNELS = List.of("SMS", "BOTH");
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final NotificationRepository notificationRepository;
    private final UserAccountRepository userAccountRepository;
    private final SmsProvider smsProvider;
    private final NotificationService notificationService;
    private final RedisLockService lockService;

    public NotificationRetryJob(NotificationRepository notificationRepository,
                                UserAccountRepository userAccountRepository,
                                SmsProvider smsProvider,
                                NotificationService notificationService,
                                RedisLockService lockService) {
        this.notificationRepository = notificationRepository;
        this.userAccountRepository = userAccountRepository;
        this.smsProvider = smsProvider;
        this.notificationService = notificationService;
        this.lockService = lockService;
    }

    @XxlJob("notificationRetryJob")
    public void notificationRetryJob() {
        lockService.runWithLock(RedisLockService.fiveMinuteKey("notification-retry"), LOCK_TTL,
                this::retryFailed);
    }

    /**
     * 返回本次尝试重试的通知数；测试可直接调用。
     */
    @Transactional
    public int retryFailed() {
        List<Notification> candidates = notificationRepository
                .findByStatusAndChannelInAndRetryCountLessThanOrderByCreatedAtAsc(
                        "FAILED", SMS_CHANNELS, MAX_RETRIES);
        int attempted = 0;
        for (Notification notification : candidates) {
            if (retryOne(notification)) {
                attempted++;
            }
        }
        if (attempted > 0) {
            log.info("retried {} failed sms notifications", attempted);
        }
        return attempted;
    }

    private boolean retryOne(Notification notification) {
        UserAccount user = userAccountRepository.findById(notification.getUserId()).orElse(null);
        if (user == null) {
            recordFailure(notification, "用户不存在，无法发送短信");
            return true;
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (notification.getRefType() != null) {
            params.put("refType", notification.getRefType());
        }
        if (notification.getRefId() != null) {
            params.put("refId", notification.getRefId());
        }
        try {
            String result = smsProvider.send(notificationService.decryptPhone(user),
                    notification.getType(), params);
            if (result == null || result.isBlank()) {
                recordFailure(notification, "短信发送返回空");
            } else {
                notification.setStatus("SENT");
                notification.setLastError(null);
                notificationRepository.save(notification);
            }
        } catch (Exception ex) {
            recordFailure(notification, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return true;
    }

    private void recordFailure(Notification notification, String error) {
        notification.setStatus("FAILED");
        notification.setRetryCount(Math.min(notification.getRetryCount() + 1, MAX_RETRIES));
        notification.setLastError(truncate(error));
        notificationRepository.save(notification);
    }

    private String truncate(String value) {
        if (value == null) {
            return "短信发送失败";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
