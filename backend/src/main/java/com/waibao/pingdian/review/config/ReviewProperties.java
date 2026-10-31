package com.waibao.pingdian.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 点评业务默认参数（对应 spec「默认参数（可配置）」）。
 */
@ConfigurationProperties(prefix = "review")
public class ReviewProperties {

    private int editWindowDays = 7;
    private int maxEdits = 2;
    private int withdrawBanDays = 30;
    private int companyCap = 3;
    private int minIntervalDays = 90;
    private int copyDetectCompanies = 3;
    private int draftTtlDays = 7;
    private Risk risk = new Risk();

    public int getEditWindowDays() {
        return editWindowDays;
    }

    public void setEditWindowDays(int editWindowDays) {
        this.editWindowDays = editWindowDays;
    }

    public int getMaxEdits() {
        return maxEdits;
    }

    public void setMaxEdits(int maxEdits) {
        this.maxEdits = maxEdits;
    }

    public int getWithdrawBanDays() {
        return withdrawBanDays;
    }

    public void setWithdrawBanDays(int withdrawBanDays) {
        this.withdrawBanDays = withdrawBanDays;
    }

    public int getCompanyCap() {
        return companyCap;
    }

    public void setCompanyCap(int companyCap) {
        this.companyCap = companyCap;
    }

    public int getMinIntervalDays() {
        return minIntervalDays;
    }

    public void setMinIntervalDays(int minIntervalDays) {
        this.minIntervalDays = minIntervalDays;
    }

    public int getCopyDetectCompanies() {
        return copyDetectCompanies;
    }

    public void setCopyDetectCompanies(int copyDetectCompanies) {
        this.copyDetectCompanies = copyDetectCompanies;
    }

    public int getDraftTtlDays() {
        return draftTtlDays;
    }

    public void setDraftTtlDays(int draftTtlDays) {
        this.draftTtlDays = draftTtlDays;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public static class Risk {
        private int burstWindowHours = 24;
        private int burstThreshold = 5;
        private int signalTtlHours = 24;

        public int getBurstWindowHours() {
            return burstWindowHours;
        }

        public void setBurstWindowHours(int burstWindowHours) {
            this.burstWindowHours = burstWindowHours;
        }

        public int getBurstThreshold() {
            return burstThreshold;
        }

        public void setBurstThreshold(int burstThreshold) {
            this.burstThreshold = burstThreshold;
        }

        public int getSignalTtlHours() {
            return signalTtlHours;
        }

        public void setSignalTtlHours(int signalTtlHours) {
            this.signalTtlHours = signalTtlHours;
        }
    }
}
