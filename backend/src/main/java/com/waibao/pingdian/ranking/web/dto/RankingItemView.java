package com.waibao.pingdian.ranking.web.dto;

/**
 * 榜单条目：只呈现可核对指标，不含任何定性词。
 */
public record RankingItemView(
        int rank,
        String uscc,
        String name,
        int sampleCount,
        Double weightedScore,
        Double lowScoreConcentration,
        Double disputeRate,
        Double score,
        Double lowScoreRatio,
        Double disputeRatio
) {
}
