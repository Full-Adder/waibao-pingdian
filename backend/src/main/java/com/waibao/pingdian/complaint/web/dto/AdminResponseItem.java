package com.waibao.pingdian.complaint.web.dto;

import com.waibao.pingdian.complaint.domain.EnterpriseResponseStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminResponseItem(
        UUID id,
        UUID reviewId,
        UUID complaintId,
        String uscc,
        String content,
        EnterpriseResponseStatus status,
        String rejectReason,
        Instant createdAt,
        Instant reviewedAt) {
}
