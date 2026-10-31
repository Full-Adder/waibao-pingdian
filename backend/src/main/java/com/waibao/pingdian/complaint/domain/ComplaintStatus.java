package com.waibao.pingdian.complaint.domain;

/**
 * 投诉工单状态，与 V1 complaint.status CHECK 约束一致。
 */
public enum ComplaintStatus {
    PENDING_VERIFY,
    REJECTED,
    EVIDENCE_COLLECTING,
    PENDING_RULING,
    RULED_USER_WIN,
    RULED_COMPANY_WIN,
    RULED_INSUFFICIENT
}
