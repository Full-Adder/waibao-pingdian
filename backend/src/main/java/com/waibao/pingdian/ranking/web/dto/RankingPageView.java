package com.waibao.pingdian.ranking.web.dto;

import com.waibao.pingdian.ranking.domain.RankingType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RankingPageView(
        String city,
        String industry,
        RankingType type,
        String algorithmVersion,
        Instant computedAt,
        Double threshold,
        Map<String, Object> params,
        MetricDefinitions metricDefinitions,
        int page,
        int size,
        long total,
        List<RankingItemView> items
) {
}
