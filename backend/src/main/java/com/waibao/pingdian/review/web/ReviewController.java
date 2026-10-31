package com.waibao.pingdian.review.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.review.service.CompanyReviewService;
import com.waibao.pingdian.review.service.ReviewService;
import com.waibao.pingdian.review.web.dto.AppealReviewRequest;
import com.waibao.pingdian.review.web.dto.ReviewItemView;
import com.waibao.pingdian.review.web.dto.ReviewUpsertRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 用户点评接口与公司公开点评列表。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;
    private final CompanyReviewService companyReviewService;

    public ReviewController(ReviewService reviewService, CompanyReviewService companyReviewService) {
        this.reviewService = reviewService;
        this.companyReviewService = companyReviewService;
    }

    @PostMapping("/reviews")
    public ResponseEntity<ApiResponse<ReviewItemView>> create(
            @RequestBody ReviewUpsertRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        ReviewItemView view = reviewService.createAndSubmit(request,
                ClientIpResolver.resolve(servletRequest), deviceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view));
    }

    @PostMapping("/reviews/drafts")
    public ApiResponse<ReviewItemView> saveDraft(@RequestBody ReviewUpsertRequest request) {
        return ApiResponse.ok(reviewService.saveDraft(request));
    }

    @PostMapping("/reviews/{reviewId}/submit")
    public ApiResponse<ReviewItemView> submit(
            @PathVariable UUID reviewId,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(reviewService.submitDraft(reviewId,
                ClientIpResolver.resolve(servletRequest), deviceId));
    }

    @PatchMapping("/reviews/{reviewId}")
    public ApiResponse<ReviewItemView> update(
            @PathVariable UUID reviewId,
            @RequestBody ReviewUpsertRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(reviewService.update(reviewId, request,
                ClientIpResolver.resolve(servletRequest), deviceId));
    }

    @PostMapping("/reviews/{reviewId}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable UUID reviewId) {
        reviewService.withdraw(reviewId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/reviews/{reviewId}/appeal")
    public ApiResponse<ReviewItemView> appeal(@PathVariable UUID reviewId,
                                              @RequestBody(required = false) AppealReviewRequest body) {
        return ApiResponse.ok(reviewService.appeal(reviewId, body == null ? null : body.statement()));
    }

    @GetMapping("/reviews/mine")
    public ApiResponse<PageResult<ReviewItemView>> mine(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reviewService.mine(status, page, size));
    }

    @GetMapping("/reviews/latest")
    public ApiResponse<java.util.List<ReviewItemView>> latest(
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(reviewService.latest(limit));
    }

    @GetMapping("/reviews/{reviewId}")
    public ApiResponse<ReviewItemView> detail(@PathVariable UUID reviewId) {
        return ApiResponse.ok(reviewService.detail(reviewId));
    }

    @GetMapping("/companies/{uscc}/reviews")
    public ApiResponse<PageResult<ReviewItemView>> companyReviews(
            @PathVariable String uscc,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(companyReviewService.list(uscc, page, size));
    }
}
