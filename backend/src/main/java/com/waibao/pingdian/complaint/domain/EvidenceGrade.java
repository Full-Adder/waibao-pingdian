package com.waibao.pingdian.complaint.domain;

/**
 * 证据分级：A 强 / B 中 / C 弱；NONE 仅用于裁定输入，不落库。
 */
public enum EvidenceGrade {
    A,
    B,
    C,
    NONE;

    public static EvidenceGrade from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return switch (raw.trim().toUpperCase()) {
            case "A" -> A;
            case "B" -> B;
            case "C" -> C;
            case "NONE" -> NONE;
            default -> throw new IllegalArgumentException("证据等级不正确");
        };
    }

    /**
     * 落库值：NONE 以 null 表示（V2 CHECK 只允许 A/B/C）。
     */
    public String persisted() {
        return this == NONE ? null : name();
    }
}
