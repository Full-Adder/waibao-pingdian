package com.waibao.pingdian.review.web.dto;

import com.waibao.pingdian.review.domain.ReviewStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 后台点评详情：原文、AI 命中项、规则版本、review_audit 历史与材料入口。
 */
public record ReviewQueueDetail(
        UUID reviewId,
        String uscc,
        String companyName,
        String identityAlias,
        int totalScore,
        List<ReviewItemView.DimensionView> dimensions,
        String content,
        boolean hasEvidence,
        List<String> aiFlags,
        BigDecimal aiConfidence,
        String rulesVersion,
        ReviewStatus status,
        String source,
        Instant enteredQueueAt,
        long waitingHours,
        boolean overdue,
        boolean noAppeal,
        boolean canAppeal,
        List<ReviewAuditView> auditHistory,
        List<ReviewEvidenceMaterialView> materials
) {
}
