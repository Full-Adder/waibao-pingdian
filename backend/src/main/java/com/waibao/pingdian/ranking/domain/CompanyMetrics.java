package com.waibao.pingdian.ranking.domain;

/**
 * 单公司在榜单口径下的样本指标。
 * recommendSample = 当前 PUBLISHED 评价数；improveSample = PUBLISHED + DISPUTED 评价数。
 */
public record CompanyMetrics(
        String uscc,
        String name,
        int recommendSample,
        double weightedScore,
        int improveSample,
        int lowScoreCount,
        double lowScoreConcentration,
        int disputedCount,
        double disputeRate,
        double improveScore
) {

    public boolean recommendEligible(int minSample) {
        return recommendSample >= minSample;
    }

    public boolean improveEligible(int minSample, double lowThreshold, double disputeThreshold) {
        return improveSample >= minSample
                && (lowScoreConcentration >= lowThreshold || disputeRate >= disputeThreshold);
    }
}
