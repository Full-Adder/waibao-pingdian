package com.waibao.pingdian.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 举报默认参数（R15）。
 */
@ConfigurationProperties(prefix = "report")
public class ReportProperties {

    private int autoManualThreshold = 5;
    private int perTarget24h = 1;

    public int getAutoManualThreshold() {
        return autoManualThreshold;
    }

    public void setAutoManualThreshold(int autoManualThreshold) {
        this.autoManualThreshold = autoManualThreshold;
    }

    public int getPerTarget24h() {
        return perTarget24h;
    }

    public void setPerTarget24h(int perTarget24h) {
        this.perTarget24h = perTarget24h;
    }
}
