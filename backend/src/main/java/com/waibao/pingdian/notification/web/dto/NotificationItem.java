package com.waibao.pingdian.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 站内通知条目，字段与 openapi NotificationItem 一致。
 */
public record NotificationItem(
        UUID id,
        String type,
        String title,
        String body,
        String channel,
        Instant readAt,
        String refType,
        String refId,
        Instant createdAt) {
}
