package com.waibao.pingdian.dashboard.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 数据看板响应：北极星 + 五组指标 + 漏斗 + 三条红线 + 口径说明。
 * metrics 为 14 项指标的扁平列表，便于看板直接渲染；分组字段保持 PRD 结构。
 */
public record DashboardResponse(
        DashboardWindow window,
        MetricView northstar,
        List<MetricView> supply,
        List<MetricView> coverage,
        List<MetricView> quality,
        List<MetricView> demand,
        List<MetricView> ecology,
        List<MetricView> metrics,
        List<FunnelStage> funnel,
        List<HealthRedLine> healthRedLines,
        List<HealthRedLine> redLines,
        Map<String, String> metricDefinitions
) {
}
