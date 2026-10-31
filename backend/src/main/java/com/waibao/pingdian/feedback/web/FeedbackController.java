package com.waibao.pingdian.feedback.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.feedback.service.FeedbackService;
import com.waibao.pingdian.feedback.web.dto.FeedbackSubmitRequest;
import com.waibao.pingdian.feedback.web.dto.FeedbackSubmitResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帮助与反馈提交（F28，公开）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FeedbackSubmitResult>> submit(
            @Valid @RequestBody FeedbackSubmitRequest request) {
        FeedbackSubmitResult result = feedbackService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }
}
