package com.waibao.pingdian.notification.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.notification.web.dto.NotificationPageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 站内通知中心。五类时限通知为必达，不存在退订接口（R16）。
 */
@RestController
@RequestMapping("/api/users/me/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<NotificationPageView> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notificationService.listMine(unreadOnly, page, size));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> read(@PathVariable UUID notificationId) {
        notificationService.markRead(notificationId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll() {
        notificationService.markAllRead();
        return ApiResponse.ok(null);
    }
}
