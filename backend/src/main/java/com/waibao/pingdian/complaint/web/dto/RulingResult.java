package com.waibao.pingdian.complaint.web.dto;

import com.waibao.pingdian.complaint.domain.ComplaintStatus;

import java.time.Instant;
import java.util.UUID;

public record RulingResult(
        UUID complaintId,
        ComplaintStatus status,
        String result,
        boolean pendingDualReview,
        boolean overdue,
        Instant rulingDeadline) {
}
