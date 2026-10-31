package com.waibao.pingdian.review.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * review_audit 历史条目。
 */
public record ReviewAuditView(
        String stage,
        String action,
        String fromStatus,
        String toStatus,
        String actorType,
        UUID actorId,
        String reasonCode,
        String detail,
        Instant createdAt
) {
}
