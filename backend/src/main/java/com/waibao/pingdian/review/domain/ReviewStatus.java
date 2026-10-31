package com.waibao.pingdian.review.domain;

/**
 * 点评 10 状态（PRD 第 6 章）。
 */
public enum ReviewStatus {
    DRAFT,
    MACHINE_REVIEWING,
    PENDING_MANUAL,
    PUBLISHED,
    EDITING,
    WITHDRAWN,
    DISPUTED,
    FOLDED,
    REJECTED,
    REMOVED
}
