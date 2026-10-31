package com.waibao.pingdian.review.domain;

/**
 * 人工驳回原因码（R11）。PRIVACY/ADVERTISING 属于红线类，驳回后不可申诉。
 */
public enum ReviewRejectReasonCode {

    INSUFFICIENT_EVIDENCE("证据不足，无法支持该指控"),
    FALSE_CLAIM("包含未经证实的不实指控"),
    ABUSIVE_LANGUAGE("包含人身攻击或侮辱性语言"),
    ADVERTISING("包含广告或联系方式"),
    PRIVACY("泄露个人隐私信息"),
    OTHER("其他不符合社区规范的内容");

    private final String message;

    ReviewRejectReasonCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public static ReviewRejectReasonCode from(String raw) {
        String code = raw.trim().toUpperCase();
        try {
            return valueOf(code);
        } catch (IllegalArgumentException ex) {
            // 兼容 OpenAPI RejectReasonCode 的旧命名。
            return switch (code) {
                case "AD" -> ADVERTISING;
                case "ABUSE" -> ABUSIVE_LANGUAGE;
                case "FALSE_FACT" -> FALSE_CLAIM;
                case "AI_TRACE", "LOW_QUALITY", "COPY",
                     "VERIFICATION_MISMATCH", "MATERIAL_UNCLEAR" -> OTHER;
                default -> throw ex;
            };
        }
    }
}
