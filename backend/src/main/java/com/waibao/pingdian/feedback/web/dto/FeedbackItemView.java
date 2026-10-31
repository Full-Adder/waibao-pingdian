package com.waibao.pingdian.feedback.web.dto;

import com.waibao.pingdian.feedback.domain.FeedbackCategory;
import com.waibao.pingdian.feedback.domain.FeedbackStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理端反馈工单条目。联系方式只返回掩码，不返回密文或明文。
 */
public record FeedbackItemView(
        UUID id,
        FeedbackCategory category,
        String contactMasked,
        String content,
        FeedbackStatus status,
        String responseNote,
        UUID respondedBy,
        Instant respondedAt,
        Instant createdAt
) {
}
