package com.waibao.pingdian.dashboard.web.dto;

/**
 * 单个指标：value 与 unit 用于展示，definition 为 PRD 17.1 口径文本；
 * formula 与 definition 同义，兼容既有看板契约命名。
 * numerator / denominator 在比率型指标适用，计数型指标为空。
 */
public record MetricView(
        String key,
        String label,
        double value,
        String unit,
        String definition,
        String formula,
        Long numerator,
        Long denominator
) {
}
