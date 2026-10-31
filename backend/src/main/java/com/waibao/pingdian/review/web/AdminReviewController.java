package com.waibao.pingdian.review.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.review.service.ReviewModerationService;
import com.waibao.pingdian.review.web.dto.ReasonTemplateBundle;
import com.waibao.pingdian.review.web.dto.ReviewBatchDispositionRequest;
import com.waibao.pingdian.review.web.dto.ReviewBatchDispositionResult;
import com.waibao.pingdian.review.web.dto.ReviewDispositionRequest;
import com.waibao.pingdian.review.web.dto.ReviewQueueDetail;
import com.waibao.pingdian.review.web.dto.ReviewQueueItem;
import com.waibao.pingdian.security.CurrentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 管理端人工审核：队列、详情、通过/折叠/驳回、批量处置与理由模板。
 * 不提供任何删除接口（AC-15）。
 */
@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {

    private final ReviewModerationService moderationService;
    private final CurrentAdminService currentAdminService;

    public AdminReviewController(ReviewModerationService moderationService,
                                 CurrentAdminService currentAdminService) {
        this.moderationService = moderationService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping("/queue")
    public ApiResponse<PageResult<ReviewQueueItem>> queue(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(moderationService.queue(source, keyword, page, size));
    }

    @GetMapping("/queue/{reviewId}")
    public ApiResponse<ReviewQueueDetail> detail(@PathVariable UUID reviewId) {
        return ApiResponse.ok(moderationService.detail(reviewId));
    }

    @PostMapping("/{reviewId}/approve")
    public ApiResponse<Void> approve(@PathVariable UUID reviewId,
                                     @RequestBody(required = false) ReviewDispositionRequest body,
                                     HttpServletRequest request) {
        ReviewDispositionRequest safe = body == null
                ? new ReviewDispositionRequest(null, null) : body;
        AdminUser admin = currentAdminService.getAdmin();
        moderationService.approve(reviewId, safe.reasonCode(), safe.note(),
                admin.getId(), admin.getRole(), ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{reviewId}/fold")
    public ApiResponse<Void> fold(@PathVariable UUID reviewId,
                                  @RequestBody(required = false) ReviewDispositionRequest body,
                                  HttpServletRequest request) {
        ReviewDispositionRequest safe = body == null
                ? new ReviewDispositionRequest(null, null) : body;
        AdminUser admin = currentAdminService.getAdmin();
        moderationService.fold(reviewId, safe.reasonCode(), safe.note(),
                admin.getId(), admin.getRole(), ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{reviewId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID reviewId,
                                    @RequestBody(required = false) ReviewDispositionRequest body,
                                    HttpServletRequest request) {
        ReviewDispositionRequest safe = body == null
                ? new ReviewDispositionRequest(null, null) : body;
        AdminUser admin = currentAdminService.getAdmin();
        moderationService.reject(reviewId, safe.reasonCode(), safe.note(),
                admin.getId(), admin.getRole(), ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/batch")
    public ApiResponse<ReviewBatchDispositionResult> batch(@RequestBody ReviewBatchDispositionRequest body,
                                                           HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        return ApiResponse.ok(moderationService.batch(body, admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request)));
    }

    @GetMapping("/reason-templates")
    public ApiResponse<ReasonTemplateBundle> reasonTemplates() {
        return ApiResponse.ok(moderationService.reasonTemplates());
    }
}
