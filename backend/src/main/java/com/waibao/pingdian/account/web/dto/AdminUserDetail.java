package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminUserDetail(
        UUID id,
        String phone,
        String phoneMasked,
        RealnameStatus realnameStatus,
        int contributionScore,
        UserStatus status,
        Instant createdAt,
        String alias,
        long approvedVerificationCount,
        long publishedReviewCount,
        List<VerificationSummary> verifications,
        Map<String, Long> reviewStatusCounts
) {
}
