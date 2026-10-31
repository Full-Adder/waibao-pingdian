package com.waibao.pingdian.verification.web.dto;

import com.waibao.pingdian.verification.domain.MaterialStatus;

import java.time.Instant;
import java.util.UUID;

public record MaterialDeletionResult(
        UUID verificationId,
        MaterialStatus materialStatus,
        Instant materialDeletedAt
) {
}
