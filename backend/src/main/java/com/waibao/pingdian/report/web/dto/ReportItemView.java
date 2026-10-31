package com.waibao.pingdian.report.web.dto;

import com.waibao.pingdian.report.domain.ReportStatus;
import com.waibao.pingdian.report.domain.ReportTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理端举报条目。thresholdReached 表示该对象被不同账号举报数已达自动进人工阈值。
 */
public record ReportItemView(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        String reasonCode,
        String detail,
        ReportStatus status,
        Instant createdAt,
        boolean thresholdReached
) {
}
