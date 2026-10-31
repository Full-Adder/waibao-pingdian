package com.waibao.pingdian.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.notification.domain.Notification;
import com.waibao.pingdian.notification.repo.NotificationRepository;
import com.waibao.pingdian.notification.service.NotificationSender;
import com.waibao.pingdian.provider.SmsProvider;
import com.waibao.pingdian.provider.mock.MockSmsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationSender 同步模式失败计数：首次失败 1，逐次递增到 3 后不再重试。
 */
class NotificationSenderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationSender notificationSender;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SmsProvider smsProvider;

    @Autowired
    private ProvidersProperties providersProperties;

    @AfterEach
    void restoreSmsProvider() {
        providersProperties.getMock().getSms().setFailFirstAttempts(0);
        mockSms().resetNotificationFailureCounter();
    }

    private MockSmsProvider mockSms() {
        return (MockSmsProvider) smsProvider;
    }

    @Test
    void smsFailureIncrementsRetryCountAndStopsAtThree() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        providersProperties.getMock().getSms().setFailFirstAttempts(10);
        mockSms().resetNotificationFailureCounter();

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType("DISPUTE_STARTED");
        notification.setTitle("争议开始");
        notification.setBody("短信失败重试计数测试");
        notification.setChannel("BOTH");
        notification.setStatus("PENDING");
        notification.setRefType("COMPLAINT");
        notification.setRefId(UUID.randomUUID().toString());
        notificationRepository.saveAndFlush(notification);
        UUID notificationId = notification.getId();

        notificationSender.deliver(notificationId, 0);
        Notification first = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(first.getStatus()).isEqualTo("FAILED");
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(first.getLastError()).contains("injected failure");

        notificationSender.deliver(notificationId, 1);
        assertThat(notificationRepository.findById(notificationId).orElseThrow().getRetryCount())
                .isEqualTo(2);

        notificationSender.deliver(notificationId, 2);
        Notification third = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(third.getStatus()).isEqualTo("FAILED");
        assertThat(third.getRetryCount()).isEqualTo(3);

        // 达到 3 次后重复消息不再投递。
        notificationSender.deliver(notificationId, 3);
        Notification after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getRetryCount()).isEqualTo(3);
        assertThat(after.getStatus()).isEqualTo("FAILED");
    }
}
