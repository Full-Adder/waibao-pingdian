package com.waibao.pingdian.report.web.dto;

import com.waibao.pingdian.report.domain.ReportStatus;

import java.util.UUID;

public record ReportSubmitView(
        UUID reportId,
        ReportStatus status,
        boolean thresholdReached
) {
}
