package com.waibao.pingdian.notification.service;

import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.MqEventPublisher;
import com.waibao.pingdian.mq.MqProperties;
import com.waibao.pingdian.mq.messages.NotificationMessage;
import com.waibao.pingdian.notification.domain.Notification;
import com.waibao.pingdian.notification.domain.NotificationChannel;
import com.waibao.pingdian.notification.domain.NotificationType;
import com.waibao.pingdian.notification.repo.NotificationRepository;
import com.waibao.pingdian.notification.web.dto.NotificationItem;
import com.waibao.pingdian.notification.web.dto.NotificationPageView;
import com.waibao.pingdian.provider.SmsProvider;
import com.waibao.pingdian.security.CryptoService;
import com.waibao.pingdian.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 站内 + 短信通知统一入口（R16）。
 * 先落 notification 记录（PENDING），再按 app.mq.async 选择发布 MQ 或同步投递；
 * 短信失败只记录不阻断主流程，重试由延迟队列或 NotificationRetryJob 承担。
 */
@Service
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final SmsProvider smsProvider;
    private final CryptoService cryptoService;
    private final CurrentUserService currentUserService;
    private final NotificationSender notificationSender;
    private final MqEventPublisher eventPublisher;
    private final MqProperties mqProperties;

    public NotificationService(NotificationRepository notificationRepository,
                               SmsProvider smsProvider,
                               CryptoService cryptoService,
                               CurrentUserService currentUserService,
                               NotificationSender notificationSender,
                               MqEventPublisher eventPublisher,
                               MqProperties mqProperties) {
        this.notificationRepository = notificationRepository;
        this.smsProvider = smsProvider;
        this.cryptoService = cryptoService;
        this.currentUserService = currentUserService;
        this.notificationSender = notificationSender;
        this.eventPublisher = eventPublisher;
        this.mqProperties = mqProperties;
    }

    // ------------------------------------------------------------------
    // 统一发送入口
    // ------------------------------------------------------------------

    @Transactional
    public Notification send(UUID userId, NotificationType type, String title, String body,
                             NotificationChannel channel, String refType, String refId) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type.name());
        notification.setTitle(title);
        notification.setBody(body);
        notification.setChannel(channel.name());
        notification.setStatus("PENDING");
        notification.setRefType(refType);
        notification.setRefId(refId);
        notificationRepository.saveAndFlush(notification);

        if (mqProperties.isAsync()) {
            eventPublisher.publishAfterCommit(RabbitConfig.ROUTING_NOTIFICATION,
                    new NotificationMessage(notification.getId(), 0));
        } else {
            notificationSender.deliver(notification.getId(), 0);
        }
        return notification;
    }

    /**
     * 幂等发送：同一用户 + 类型 + refType + refId 只写一条，供定时提醒使用。
     */
    @Transactional
    public Notification sendOnce(UUID userId, NotificationType type, String title, String body,
                                 NotificationChannel channel, String refType, String refId) {
        if (refType != null && refId != null
                && notificationRepository.existsByUserIdAndTypeAndRefTypeAndRefId(
                userId, type.name(), refType, refId)) {
            return null;
        }
        return send(userId, type, title, body, channel, refType, refId);
    }

    @Transactional
    public void notifyVerificationRejected(UserAccount user, UUID verificationId,
                                           String reasonCode, String reasonLabel) {
        send(user.getId(), NotificationType.VERIFICATION_REJECTED, "在职佐证未通过审核",
                "你的在职佐证材料未通过审核，原因：" + reasonLabel + "。请补充材料后重新提交。",
                NotificationChannel.BOTH, "VERIFICATION", verificationId.toString());
    }

    /**
     * 企业联系人短信：联系人不是平台用户，只发送不落 notification 表。
     */
    public boolean sendSms(String phone, String template, Map<String, String> params) {
        try {
            String result = smsProvider.send(phone, template, params);
            return result != null && !result.isBlank();
        } catch (Exception ignored) {
            // 短信失败由调用方审计，不阻断主流程。
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 通知中心
    // ------------------------------------------------------------------

    public NotificationPageView listMine(boolean unreadOnly, int page, int size) {
        UUID userId = currentUserService.getUserId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<Notification> result = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<NotificationItem> items = result.getContent().stream().map(this::toItem).toList();
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNull(userId);
        return new NotificationPageView(items, safePage, safeSize, result.getTotalElements(), unreadCount);
    }

    @Transactional
    public void markRead(UUID notificationId) {
        UUID userId = currentUserService.getUserId();
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "通知不存在"));
        if (!notification.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作他人通知");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notification.setStatus("READ");
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public int markAllRead() {
        UUID userId = currentUserService.getUserId();
        return notificationRepository.markAllRead(userId, Instant.now());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private NotificationItem toItem(Notification notification) {
        return new NotificationItem(notification.getId(), notification.getType(),
                notification.getTitle(), notification.getBody(), notification.getChannel(),
                notification.getReadAt(), notification.getRefType(), notification.getRefId(),
                notification.getCreatedAt());
    }

    String decryptPhone(UserAccount user) {
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
