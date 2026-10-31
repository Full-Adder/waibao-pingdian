package com.waibao.pingdian.complaint.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ComplaintEvidenceView(
        UUID id,
        String side,
        String grade,
        String note,
        String fileName,
        String materialStatus,
        Instant createdAt) {
}
