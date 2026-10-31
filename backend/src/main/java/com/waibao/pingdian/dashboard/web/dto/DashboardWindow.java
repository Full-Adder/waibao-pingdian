package com.waibao.pingdian.dashboard.web.dto;

/**
 * 指标时间窗口，日期为业务时区 Asia/Shanghai 的自然日。
 */
public record DashboardWindow(
        String from,
        String to,
        String city,
        String industry,
        String timeZone
) {
}
