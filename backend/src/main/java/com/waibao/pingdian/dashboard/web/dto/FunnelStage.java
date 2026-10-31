package com.waibao.pingdian.dashboard.web.dto;

/**
 * 供给漏斗阶段。conversionFromPrevious 为相对上一阶段的转化率（百分比），首阶段为空。
 */
public record FunnelStage(String stage, long count, Double conversionFromPrevious) {
}
