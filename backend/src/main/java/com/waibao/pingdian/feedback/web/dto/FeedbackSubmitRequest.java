package com.waibao.pingdian.feedback.web.dto;

import com.waibao.pingdian.feedback.domain.FeedbackCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 帮助与反馈提交。contact 可选，手机号/邮箱均会加密保存。
 */
public record FeedbackSubmitRequest(
        FeedbackCategory category,
        String contact,
        @NotBlank @Size(min = 10, max = 2000) String content
) {
}
