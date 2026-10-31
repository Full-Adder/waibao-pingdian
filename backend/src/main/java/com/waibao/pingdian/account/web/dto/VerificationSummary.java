package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.verification.domain.ProofType;
import com.waibao.pingdian.verification.domain.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 用户详情中的核验记录摘要，不含材料文件信息。
 */
public record VerificationSummary(
        UUID id,
        VerificationStatus status,
        String companyUscc,
        LocalDate periodStart,
        LocalDate periodEnd,
        ProofType proofType,
        Instant reviewedAt
) {
}
