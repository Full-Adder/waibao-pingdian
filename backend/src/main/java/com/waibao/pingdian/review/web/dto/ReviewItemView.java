package com.waibao.pingdian.review.web.dto;

import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.verification.domain.VerificationBadge;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 点评视图（对齐 OpenAPI ReviewItem，并补充可见性/折叠/争议/可编辑信息）。
 */
public record ReviewItemView(
        UUID id,
        String uscc,
        String companyName,
        String identityAlias,
        VerificationBadge verificationBadge,
        int totalScore,
        String content,
        List<DimensionView> dimensions,
        List<String> tags,
        String periodStart,
        String periodEnd,
        String city,
        List<EvidenceView> evidence,
        ReviewStatus status,
        BigDecimal aiConfidence,
        List<String> aiFlags,
        String aiRulesVersion,
        Instant publishedAt,
        Instant editDeadline,
        int editCount,
        Instant disputeDeadline,
        boolean everDisputed,
        String rejectReasonCode,
        String rejectMessage,
        String rejectRuleVersion,
        Instant withdrawnAt,
        Instant createdAt,
        boolean collapsed,
        String collapseNote,
        boolean disputed,
        int remainingEdits,
        boolean canAppeal,
        boolean editable,
        Object enterpriseResponse
) {

    public record DimensionView(String code, int score) {
    }

    public record EvidenceView(String type, String materialStatus, Instant maskedAt) {
    }
}
