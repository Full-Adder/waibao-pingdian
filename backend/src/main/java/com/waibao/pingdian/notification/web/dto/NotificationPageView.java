package com.waibao.pingdian.notification.web.dto;

import java.util.List;

/**
 * 通知分页视图，字段与 openapi NotificationPage 一致。
 */
public record NotificationPageView(
        List<NotificationItem> items,
        int page,
        int size,
        long total,
        long unreadCount) {
}
