package com.waibao.pingdian.complaint.domain;

/**
 * 裁定结论：用户胜 / 企业胜 / 证据不足。
 */
public enum ComplaintResult {
    USER_WIN,
    COMPANY_WIN,
    INSUFFICIENT;

    public ComplaintStatus toStatus() {
        return switch (this) {
            case USER_WIN -> ComplaintStatus.RULED_USER_WIN;
            case COMPANY_WIN -> ComplaintStatus.RULED_COMPANY_WIN;
            case INSUFFICIENT -> ComplaintStatus.RULED_INSUFFICIENT;
        };
    }
}
