package com.waibao.pingdian.complaint.web.dto;

import com.waibao.pingdian.complaint.domain.ComplaintStatus;
import com.waibao.pingdian.review.web.dto.ReviewItemView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ComplaintDetailView(
        UUID id,
        UUID reviewId,
        ReviewItemView review,
        String claimantUscc,
        boolean subjectVerified,
        String type,
        boolean amountFlag,
        boolean safetyFlag,
        ComplaintStatus status,
        Instant deadline,
        long remainingSeconds,
        boolean overdue,
        String enterpriseEvidenceNote,
        String userEvidenceNote,
        String resolution,
        Instant createdAt,
        boolean dualReviewRequired,
        String enterpriseGrade,
        String userGrade,
        String rulingReason,
        String citedMaterials,
        String ruleClause,
        String proposedResolution,
        UUID proposedBy,
        Instant proposedAt,
        List<ComplaintEvidenceView> evidences,
        List<ComplaintMaterialView> materials,
        List<EnterpriseResponseView> responses) {
}
