package com.waibao.pingdian.complaint.web.dto;

import java.util.List;
import java.util.UUID;

public record ComplaintRulingRequest(
        String result,
        String enterpriseGrade,
        String userGrade,
        String reasonText,
        List<String> citedMaterialIds,
        String ruleClause,
        Boolean requiresDualReview,
        UUID secondReviewerId) {
}
