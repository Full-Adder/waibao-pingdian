package com.waibao.pingdian.notification.domain;

/**
 * 时限类通知类型。五类均为必达，不提供退订入口（R16）。
 */
public enum NotificationType {
    DISPUTE_STARTED,
    EVIDENCE_REMINDER,
    RULING_RESULT,
    VERIFICATION_REJECTED,
    APPEAL_RESULT
}
