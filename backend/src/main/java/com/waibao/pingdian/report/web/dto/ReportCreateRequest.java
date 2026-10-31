package com.waibao.pingdian.report.web.dto;

public record ReportCreateRequest(
        String targetType,
        String targetId,
        String reasonCode,
        String detail
) {
}
