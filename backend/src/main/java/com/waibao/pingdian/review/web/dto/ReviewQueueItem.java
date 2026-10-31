package com.waibao.pingdian.review.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 人工审核队列条目；按进入待人工时间升序（等待最久优先）。
 */
public record ReviewQueueItem(
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
        String source,
        Instant enteredQueueAt,
        long waitingHours,
        boolean overdue,
        boolean noAppeal,
        boolean canAppeal
) {
}
