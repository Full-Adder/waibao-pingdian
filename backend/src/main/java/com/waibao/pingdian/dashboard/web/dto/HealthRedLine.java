package com.waibao.pingdian.dashboard.web.dto;

/**
 * 健康度红线。triggered 与 breached 含义一致，兼容 PRD 与既有契约命名；
 * suggestion 按 PRD 17.2 给出各自动作，不做统一暂停拉新。
 */
public record HealthRedLine(
        String key,
        String label,
        double value,
        double threshold,
        String comparison,
        boolean triggered,
        boolean breached,
        String suggestion
) {
}
