package com.waibao.pingdian.report.domain;

/**
 * 举报原因码。兼容旧契约中的 ABUSE / AD / FALSE_FACT 别名，统一落库为规范码。
 */
public enum ReportReasonCode {
    FALSE_INFO,
    ABUSIVE,
    ADVERTISING,
    PRIVACY,
    SPAM,
    OTHER;

    public static ReportReasonCode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("reasonCode 不能为空");
        }
        String code = raw.trim().toUpperCase();
        return switch (code) {
            case "FALSE_INFO", "FALSE_FACT" -> FALSE_INFO;
            case "ABUSIVE", "ABUSE" -> ABUSIVE;
            case "ADVERTISING", "AD" -> ADVERTISING;
            default -> valueOf(code);
        };
    }
}
