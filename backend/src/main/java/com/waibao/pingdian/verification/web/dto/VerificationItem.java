package com.waibao.pingdian.verification.web.dto;

import com.waibao.pingdian.verification.domain.MaterialStatus;
import com.waibao.pingdian.verification.domain.ProofType;
import com.waibao.pingdian.verification.domain.VerificationBadge;
import com.waibao.pingdian.verification.domain.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record VerificationItem(
        UUID id,
        UUID userId,
        ProofType proofType,
        String companyUscc,
        String companyName,
        LocalDate periodStart,
        LocalDate periodEnd,
        VerificationBadge badge,
        VerificationStatus status,
        String rejectReasonCode,
        String rejectNote,
        MaterialStatus materialStatus,
        Instant createdAt,
        Instant reviewedAt
) {
}
