package com.waibao.pingdian.retention.service;

/**
 * 保留任务最近一次执行的统计。
 */
public record RetentionResult(int userVerificationDeleted,
                              int reviewEvidenceDeleted,
                              int complaintEvidenceDeleted) {
}
