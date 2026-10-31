package com.waibao.pingdian.notification.service;

import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.mq.MqEventPublisher;
import com.waibao.pingdian.mq.MqProperties;
import com.waibao.pingdian.mq.messages.NotificationMessage;
import com.waibao.pingdian.notification.domain.Notification;
import com.waibao.pingdian.notification.repo.NotificationRepository;
import com.waibao.pingdian.provider.SmsProvider;
import com.waibao.pingdian.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通知投递：站内直接置 SENT；短信/双通道调用 SmsProvider。
 * 失败只落库不抛出，异步模式下不足 3 次时投递到延迟队列，5 分钟后回到主队列重试。
 */
@Service
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private static final int MAX_RETRIES = 3;
    private static final List<String> SMS_CHANNELS = List.of("SMS", "BOTH");

    private final NotificationRepository notificationRepository;
    private final UserAccountRepository userAccountRepository;
    private final SmsProvider smsProvider;
    private final CryptoService cryptoService;
    private final MqEventPublisher eventPublisher;
    private final MqProperties mqProperties;

    public NotificationSender(NotificationRepository notificationRepository,
                              UserAccountRepository userAccountRepository,
                              SmsProvider smsProvider,
                              CryptoService cryptoService,
                              MqEventPublisher eventPublisher,
                              MqProperties mqProperties) {
        this.notificationRepository = notificationRepository;
        this.userAccountRepository = userAccountRepository;
        this.smsProvider = smsProvider;
        this.cryptoService = cryptoService;
        this.eventPublisher = eventPublisher;
        this.mqProperties = mqProperties;
    }

    /**
     * 幂等投递：已发送/已读或重试次数耗尽的记录直接跳过，避免重复消息再次触达。
     */
    @Transactional
    public void deliver(UUID notificationId, int retryCount) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            log.warn("notification not found: {}", notificationId);
            return;
        }
        if ("SENT".equals(notification.getStatus()) || "READ".equals(notification.getStatus())) {
            return;
        }
        if ("FAILED".equals(notification.getStatus()) && notification.getRetryCount() >= MAX_RETRIES) {
            return;
        }
        if (!SMS_CHANNELS.contains(notification.getChannel())) {
            notification.setStatus("SENT");
            notification.setLastError(null);
            notificationRepository.save(notification);
            return;
        }

        UserAccount user = userAccountRepository.findById(notification.getUserId()).orElse(null);
        if (user == null) {
            recordFailure(notification, "用户不存在，无法发送短信");
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (notification.getRefType() != null) {
            params.put("refType", notification.getRefType());
        }
        if (notification.getRefId() != null) {
            params.put("refId", notification.getRefId());
        }
        try {
            String result = smsProvider.send(decryptPhone(user), notification.getType(), params);
            if (result == null || result.isBlank()) {
                recordFailure(notification, "短信发送返回空");
            } else {
                notification.setStatus("SENT");
                notification.setLastError(null);
                notificationRepository.save(notification);
            }
        } catch (Exception ex) {
            recordFailure(notification, truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
        log.debug("notification delivered id={} retryCount={} status={}",
                notificationId, retryCount, notification.getStatus());
    }

    private void recordFailure(Notification notification, String error) {
        int attempts = Math.min(notification.getRetryCount() + 1, MAX_RETRIES);
        notification.setStatus("FAILED");
        notification.setRetryCount(attempts);
        notification.setLastError(truncate(error));
        notificationRepository.save(notification);
        // 同步模式保留 NotificationRetryJob 的扫描重试；异步模式走 5 分钟延迟队列。
        if (mqProperties.isAsync() && attempts < MAX_RETRIES) {
            eventPublisher.publishDelayAfterCommit(
                    new NotificationMessage(notification.getId(), attempts));
        }
    }

    private String decryptPhone(UserAccount user) {
        try {
            return cryptoService.decrypt(user.getPhoneCipher());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("手机号解密失败");
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "短信发送失败";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
