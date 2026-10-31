package com.waibao.pingdian.ranking.web.dto;

/**
 * 指标口径说明，随榜单返回。
 */
public record MetricDefinitions(
        String sampleSet,
        String weightedScore,
        String lowScoreConcentration,
        String disputeRate
) {
}
