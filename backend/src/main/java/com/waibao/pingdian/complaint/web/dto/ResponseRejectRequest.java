package com.waibao.pingdian.complaint.web.dto;

public record ResponseRejectRequest(String reason, String reasonText, String note) {

    public String effectiveReason() {
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return reasonText;
    }
}
