package com.waibao.pingdian.complaint.web.dto;

import com.waibao.pingdian.complaint.domain.ComplaintStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MyComplaintView(
        UUID complaintId,
        UUID reviewId,
        ComplaintStatus status,
        boolean subjectVerified,
        Instant deadline,
        long remainingSeconds,
        boolean overdue,
        boolean userEvidenceSubmitted,
        boolean enterpriseEvidenceSubmitted,
        String userEvidenceNote,
        String enterpriseEvidenceNote,
        String resolution,
        boolean hasEnterpriseResponse,
        List<EnterpriseResponseView> enterpriseResponses) {
}
