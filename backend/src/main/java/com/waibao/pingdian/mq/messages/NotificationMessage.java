package com.waibao.pingdian.mq.messages;

import java.util.UUID;

/**
 * 通知发送消息：只携带 notification id 与当前重试次数。
 */
public record NotificationMessage(UUID notificationId, int retryCount) {
}
