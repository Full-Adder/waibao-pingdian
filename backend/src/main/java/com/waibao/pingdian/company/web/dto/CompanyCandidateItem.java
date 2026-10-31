package com.waibao.pingdian.company.web.dto;

import com.waibao.pingdian.company.domain.CandidateStatus;

import java.time.Instant;
import java.util.UUID;

public record CompanyCandidateItem(
        UUID id,
        String name,
        String city,
        String industry,
        UUID reporterUserId,
        String matchedUscc,
        CandidateStatus status,
        Instant createdAt
) {
}
