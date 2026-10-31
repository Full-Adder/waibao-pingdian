package com.waibao.pingdian.complaint.web.dto;

import java.time.Instant;
import java.util.UUID;

public record EnterpriseResponseView(
        UUID id,
        String reviewId,
        String content,
        Instant createdAt,
        String label) {

    public static final String LABEL = "企业回应";

    public static EnterpriseResponseView approved(UUID id, UUID reviewId, String content, Instant createdAt) {
        return new EnterpriseResponseView(id, reviewId == null ? null : reviewId.toString(),
                content, createdAt, LABEL);
    }
}
