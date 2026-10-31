package com.waibao.pingdian.review.domain;

import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;

import java.util.Locale;

/**
 * 六维度代码。数据库与 OpenAPI 冻结为 SALARY / WORK_REALITY / CONTRACT /
 * PROJECT_QUALITY / MANAGEMENT / RECOMMEND；兼容任务描述中的 AUTHENTICITY、RECOMMENDATION 别名。
 */
public enum DimensionCode {
    SALARY,
    WORK_REALITY,
    CONTRACT,
    PROJECT_QUALITY,
    MANAGEMENT,
    RECOMMEND;

    public static DimensionCode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "维度代码不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "AUTHENTICITY", "WORK_REALITY" -> WORK_REALITY;
            case "RECOMMENDATION", "RECOMMEND" -> RECOMMEND;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "维度代码不正确：" + raw);
                }
            }
        };
    }
}
