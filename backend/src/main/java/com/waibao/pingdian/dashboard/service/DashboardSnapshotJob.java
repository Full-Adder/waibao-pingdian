package com.waibao.pingdian.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.dashboard.web.dto.DashboardResponse;
import com.waibao.pingdian.dashboard.web.dto.HealthRedLine;
import com.waibao.pingdian.dashboard.web.dto.MetricView;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每日 02:30（Asia/Shanghai）由 XXL-Job 触发，计算最近 30 天全量指标，写入结构化日志并缓存。
 * 无快照表，不新增迁移。
 */
@Service
public class DashboardSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(DashboardSnapshotJob.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;

    public DashboardSnapshotJob(DashboardService dashboardService, ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper;
    }

    @XxlJob("dashboardSnapshotJob")
    public void dashboardSnapshotJob() {
        LocalDate to = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = to.minusDays(29);
        DashboardResponse response = dashboardService.computeDailySnapshot(from, to);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("windowFrom", from.toString());
        snapshot.put("windowTo", to.toString());
        snapshot.put("northstar", response.northstar().value());
        Map<String, Double> groups = new LinkedHashMap<>();
        groups.put("supply", firstValue(response, "registrationVerificationRate"));
        groups.put("coverage", firstValue(response, "companyCount"));
        groups.put("quality", firstValue(response, "aiAutoPublishRate"));
        groups.put("demand", firstValue(response, "weeklyUv"));
        groups.put("ecology", firstValue(response, "reportDensity"));
        snapshot.put("groups", groups);
        Map<String, Boolean> redLines = new LinkedHashMap<>();
        for (HealthRedLine line : response.healthRedLines()) {
            redLines.put(line.key(), line.triggered());
        }
        snapshot.put("redLines", redLines);
        try {
            log.info("dashboard_snapshot {}", objectMapper.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.warn("dashboard_snapshot serialize failed: {}", ex.getClass().getSimpleName());
        }
    }

    private static Double firstValue(DashboardResponse response, String key) {
        for (MetricView metric : response.metrics()) {
            if (key.equals(metric.key())) {
                return metric.value();
            }
        }
        return null;
    }
}
