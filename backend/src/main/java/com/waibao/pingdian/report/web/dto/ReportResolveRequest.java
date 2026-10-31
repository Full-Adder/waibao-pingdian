package com.waibao.pingdian.report.web.dto;

/**
 * 兼容契约的 status 与任务说明的 result 字段；两者二选一。
 */
public record ReportResolveRequest(
        String result,
        String status,
        String note
) {
    public String resolvedValue() {
        return result != null && !result.isBlank() ? result : status;
    }
}
