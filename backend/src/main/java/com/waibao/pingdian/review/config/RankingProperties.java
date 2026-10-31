package com.waibao.pingdian.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 点评列表加权排序参数（R12）。
 */
@ConfigurationProperties(prefix = "ranking")
public class RankingProperties {

    private String algorithmVersion = "ranking-v1";
    private int minSample = 3;
    private double recommendPercentile = 0.20;
    private double verifiedWeight = 1.0;
    private double formerWeight = 0.8;
    private double evidenceBonus = 0.2;
    private double copyPenalty = 0.5;
    private double disputedPenalty = 0.5;
    private TimeDecay timeDecay = new TimeDecay();

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public void setAlgorithmVersion(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public int getMinSample() {
        return minSample;
    }

    public void setMinSample(int minSample) {
        this.minSample = minSample;
    }

    public double getRecommendPercentile() {
        return recommendPercentile;
    }

    public void setRecommendPercentile(double recommendPercentile) {
        this.recommendPercentile = recommendPercentile;
    }

    public double getVerifiedWeight() {
        return verifiedWeight;
    }

    public void setVerifiedWeight(double verifiedWeight) {
        this.verifiedWeight = verifiedWeight;
    }

    public double getFormerWeight() {
        return formerWeight;
    }

    public void setFormerWeight(double formerWeight) {
        this.formerWeight = formerWeight;
    }

    public double getEvidenceBonus() {
        return evidenceBonus;
    }

    public void setEvidenceBonus(double evidenceBonus) {
        this.evidenceBonus = evidenceBonus;
    }

    public double getCopyPenalty() {
        return copyPenalty;
    }

    public void setCopyPenalty(double copyPenalty) {
        this.copyPenalty = copyPenalty;
    }

    public double getDisputedPenalty() {
        return disputedPenalty;
    }

    public void setDisputedPenalty(double disputedPenalty) {
        this.disputedPenalty = disputedPenalty;
    }

    public TimeDecay getTimeDecay() {
        return timeDecay;
    }

    public void setTimeDecay(TimeDecay timeDecay) {
        this.timeDecay = timeDecay;
    }

    public static class TimeDecay {
        private boolean enabled = true;
        private int months = 12;
        private double weight = 0.5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMonths() {
            return months;
        }

        public void setMonths(int months) {
            this.months = months;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }
    }
}
