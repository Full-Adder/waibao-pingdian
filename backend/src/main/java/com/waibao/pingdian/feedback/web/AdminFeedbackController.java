package com.waibao.pingdian.feedback.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.feedback.service.FeedbackService;
import com.waibao.pingdian.feedback.web.dto.FeedbackItemView;
import com.waibao.pingdian.feedback.web.dto.FeedbackReplyRequest;
import com.waibao.pingdian.security.CurrentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 管理端反馈工单列表与回复（内容管理员及以上）。不提供删除接口。
 */
@RestController
@RequestMapping("/api/admin/feedback")
public class AdminFeedbackController {

    private final FeedbackService feedbackService;
    private final CurrentAdminService currentAdminService;

    public AdminFeedbackController(FeedbackService feedbackService,
                                   CurrentAdminService currentAdminService) {
        this.feedbackService = feedbackService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<FeedbackItemView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(feedbackService.list(status, page, size));
    }

    @PostMapping("/{ticketId}/reply")
    public ApiResponse<Void> reply(@PathVariable UUID ticketId,
                                   @Valid @RequestBody FeedbackReplyRequest request,
                                   HttpServletRequest httpRequest) {
        AdminUser admin = currentAdminService.getAdmin();
        feedbackService.reply(ticketId, request, admin, ClientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }
}
